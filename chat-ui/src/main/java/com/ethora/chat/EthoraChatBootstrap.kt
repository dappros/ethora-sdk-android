package com.ethora.chat

import android.content.Context
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.networking.TokenManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.InitBeforeLoadFlow
import com.ethora.chat.core.store.IncrementalHistoryLoader
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.ConnectionStatus
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.core.xmpp.XMPPClientDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Web parity for `initBeforeLoad` (web/src/context/xmppProvider.tsx L216-332).
 *
 * Call `EthoraChatBootstrap.initialize(context, config)` from your Application
 * class (or anywhere that has a JWT) to run the full SDK bootstrap WITHOUT
 * mounting the `Chat` composable:
 *
 *   1. Apply config, set base URL + app token on `ApiClient`.
 *   2. POST /users/client via JWT — populates `UserStore`.
 *   3. GET /chats/my — populates `RoomStore` (cache first, then API).
 *   4. Create (or reuse) an `XMPPClient`, wait until connected.
 *   5. Read `chatjson` private store (XEP-0049) → sync per-room
 *      `lastViewedTimestamp` into `RoomStore`.
 *   6. Run `IncrementalHistoryLoader.updateMessagesTillLast` to preload
 *      the latest 20 messages per room.
 *
 * Once this returns, `useUnread()` / any observer of `RoomStore.rooms` reflects
 * the real server state — so the host app can render an unread dot on a home
 * screen badge long before the user opens the actual chat UI.
 *
 * The function is idempotent: subsequent calls with the same config key no-op
 * and return immediately. The composable `Chat` entry point reuses this same
 * XMPPClient via `sharedXmppClient` so only ONE socket is ever opened.
 */
object EthoraChatBootstrap {
    private const val TAG = "EthoraChatBootstrap"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val bootstrapMutex = Mutex()

    // Reference to the XMPP client that was created here, so the `Chat`
    // composable (or any other caller) can pick it up instead of building a
    // new connection. Cleared on `shutdown()`.
    private val _sharedXmppClient = MutableStateFlow<XMPPClient?>(null)
    val sharedXmppClient: StateFlow<XMPPClient?> = _sharedXmppClient.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var lastBootstrapKey: String? = null

    // Rate-limit retries for the same key after a failure so recompositions
    // don't spawn parallel XMPPClient reconnect loops while DNS or the
    // backend is down. A different key (new user/JWT/baseUrl) bypasses.
    private const val FAILURE_COOLDOWN_MS = 15_000L
    private var lastFailedKey: String? = null
    private var lastFailedAtMs: Long = 0L

    @Volatile private var logoutHookWired = false

    /**
     * Fire-and-forget entry point — the supported way to kick off the background
     * bootstrap BEFORE any composable mounts, mirroring web's xmppProvider.tsx
     * useEffect that runs on app root mount.
     *
     * Call from your Application's onCreate:
     * ```
     * override fun onCreate() {
     *     super.onCreate()
     *     initEthoraSdk(this)          // one-time persistence/store init
     *     val config = ChatConfig.Builder(applicationContext)
     *         .initBeforeLoad(true)
     *         .jwtLogin(JwtLogin(enabled = true, token = storedJwt))
     *         .baseUrl("https://api.dappros.com/v1")
     *         .xmppSettings(XMPPSettings(host = "chat.dappros.com", port = 5443))
     *         .build()
     *     EthoraChatBootstrap.initializeAsync(applicationContext, config)
     * }
     * ```
     *
     * Once this returns, the 8-stage flow runs on a background coroutine:
     *  1. ChatStore.setConfig + ApiClient.setBaseUrl.
     *  2. Resolve user — prefer UserStore persistence, else POST /users/client
     *     with the configured JWT and save the result.
     *  3. GET /chats/my — populate RoomStore (cache-first, then API refresh).
     *  4. Create or reuse the shared XMPPClient with the resolved creds.
     *  5. Wait up to 8 s for XMPP to come online.
     *  6. Read chatjson private store (XEP-0049) → per-room
     *     `lastViewedTimestamp` sync so unread badges match other devices.
     *  7. Preload 30 messages × N rooms in batches of 5 via MessageLoader
     *     (web parity with runHistoryPreloadScheduler).
     *  8. IncrementalHistoryLoader.updateMessagesTillLast for delta catchup.
     *
     * Properties:
     * • Thread-safe; serialized by an internal Mutex.
     * • Idempotent: successful runs cache the config key; repeated calls with
     *   the same key no-op. Failed / partial runs do NOT cache, so hosts can
     *   safely call this on every app resume.
     * • Swallows all errors to keep the host app alive.
     *
     * Host code can observe [isInitialized] or [RoomStore.rooms] /
     * [useUnread()] to render badges before the Chat composable mounts.
     */
    fun initializeAsync(context: Context, config: ChatConfig) {
        scope.launch { initialize(context, config) }
    }

    suspend fun initialize(context: Context, config: ChatConfig) {
        // LogoutService must clear our cached key/flags too — otherwise
        // re-login with the same bootstrap key short-circuits.
        if (!logoutHookWired) {
            synchronized(this) {
                if (!logoutHookWired) {
                    com.ethora.chat.core.service.LogoutService.setInternalShutdownHook {
                        shutdownBlocking()
                    }
                    logoutHookWired = true
                }
            }
        }
        // EVERYTHING the bootstrap does is best-effort. The one absolute rule
        // is that it must NEVER crash the host app — otherwise wrapping the
        // app root in EthoraChatProvider becomes a liability. A CancellationException
        // must still propagate so structured concurrency keeps working.
        try {
            bootstrapMutex.withLock {
                runBootstrapLocked(context, config)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Bootstrap crashed; swallowing to keep host alive", t)
        }
    }

    private suspend fun runBootstrapLocked(context: Context, config: ChatConfig) {
        val key = bootstrapKey(config)
        if (lastBootstrapKey == key && _isInitialized.value) {
            android.util.Log.d(TAG, "Bootstrap already complete for key=${key.take(40)}…")
            return
        }

        val now = System.currentTimeMillis()
        if (lastFailedKey == key && (now - lastFailedAtMs) < FAILURE_COOLDOWN_MS) {
            val remain = FAILURE_COOLDOWN_MS - (now - lastFailedAtMs)
            android.util.Log.w(TAG, "Bootstrap in failure cooldown for ${remain}ms (key=${key.take(40)}…) — skipping")
            return
        }

        // Commit the key + initialized flag only on full success so partial
        // failures (e.g. XMPP connect timeout) don't poison the cache.
        var committedKey = false
        try {
            // 1. Config + ApiClient base
            try {
                ChatStore.setConfig(config)
                val baseUrl = config.baseUrl ?: com.ethora.chat.core.config.AppConfig.defaultBaseURL
                ApiClient.setBaseUrl(baseUrl, config.customAppToken)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ChatStore/ApiClient init failed", e)
                return
            }

            val localStorage = try { LocalStorage(context) } catch (e: Exception) {
                android.util.Log.w(TAG, "LocalStorage init failed", e)
                return
            }

            // 2. Resolve user.
            try {
                if (UserStore.currentUser.value == null) {
                    val persisted = UserStore.loadUserFromPersistence()
                    if (persisted != null) {
                        UserStore.setUser(persisted)
                        ApiClient.setUserToken(persisted.token ?: "")
                    } else {
                        val jwt = config.jwtLogin?.takeIf { it.enabled }?.token
                            ?: localStorage.getJWTToken()
                        if (jwt.isNullOrBlank()) {
                            android.util.Log.w(TAG, "No JWT available; cannot auto-login")
                            return
                        }
                        val login = withContext(Dispatchers.IO) {
                            AuthAPIHelper.loginViaJWT(jwt)
                        }
                        if (login == null) {
                            android.util.Log.e(TAG, "JWT /users/client failed")
                            return
                        }
                        UserStore.setUser(login)
                        ApiClient.setUserToken(login.token)
                        localStorage.saveJWTToken(jwt)
                        try { TokenManager.startAutoRefresh() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "User resolution failed", e)
                return
            }

            val user = UserStore.currentUser.value
            val xmppUser = user?.xmppUsername
            val xmppPass = user?.xmppPassword
            if (xmppUser.isNullOrBlank() || xmppPass.isNullOrBlank()) {
                android.util.Log.w(TAG, "Resolved user is missing XMPP creds (user=$xmppUser)")
                return
            }

            // 3. Rooms
            try {
                val cached = withContext(Dispatchers.IO) { RoomStore.loadRoomsFromPersistence() }
                if (cached.isNotEmpty() && RoomStore.rooms.value.isEmpty()) {
                    RoomStore.setRooms(cached)
                }
                val remote = withContext(Dispatchers.IO) { RoomsAPIHelper.getRooms() }
                if (remote.isNotEmpty()) RoomStore.setRooms(remote)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Rooms fetch failed", e)
            }

            // 4. XMPP client. Disconnect any stale shared instance before
            // replacing it, otherwise its reconnect loop runs in parallel
            // with the new one.
            val client = try {
                val existing = _sharedXmppClient.value
                if (existing != null && existing.checkOnline()) {
                    existing
                } else {
                    if (existing != null) {
                        runCatching { existing.disconnect() }
                            .onFailure { android.util.Log.w(TAG, "stale shared XMPPClient disconnect threw", it) }
                    }
                    val created = XMPPClient(
                        username = xmppUser,
                        password = xmppPass,
                        settings = config.xmppSettings,
                        dnsFallbackOverrides = config.dnsFallbackOverrides
                    )
                    created.setDelegate(object : XMPPClientDelegate {
                        override fun onXMPPClientConnected(client: XMPPClient) {}
                        override fun onXMPPClientDisconnected(client: XMPPClient) {}
                        override fun onMessageReceived(client: XMPPClient, message: com.ethora.chat.core.models.Message) {}
                        override fun onStanzaReceived(client: XMPPClient, stanza: com.ethora.chat.core.xmpp.XMPPStanza) {}
                        override fun onStatusChanged(client: XMPPClient, status: ConnectionStatus) {}
                        override fun onComposingReceived(client: XMPPClient, roomJid: String, isComposing: Boolean, composingList: List<String>) {}
                        override fun onMessageEdited(client: XMPPClient, roomJid: String, messageId: String, newText: String) {}
                        override fun onReactionReceived(client: XMPPClient, roomJid: String, messageId: String, from: String, reactions: List<String>, data: Map<String, String>) {}
                    })
                    _sharedXmppClient.value = created
                    scope.launch {
                        try { created.initializeClient() } catch (e: Exception) {
                            android.util.Log.w(TAG, "XMPP initializeClient failed", e)
                        }
                    }
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "XMPP setup failed", e)
                return
            }

            // 5. Wait for connection.
            val ready = try { client.ensureConnected(8_000) } catch (_: Exception) { false }
            if (!ready) {
                android.util.Log.w(TAG, "XMPP did not come online within 8s; leaving bootstrap uncommitted for retry")
                return
            }

            // 6. Private store (chatjson) → per-room lastViewedTimestamp.
            try { InitBeforeLoadFlow.run(client) } catch (e: Exception) {
                android.util.Log.w(TAG, "InitBeforeLoadFlow.run failed", e)
            }

            // 7. Full history preload — this is the web `runHistoryPreloadScheduler`
            //    equivalent. Loads 30 messages per room in batches of 5. Must run
            //    here (not via a delegate callback) because the delegate-driven
            //    path only fires when XMPPClient TRANSITIONS to connected — which
            //    doesn't happen when Chat later reuses an already-connected
            //    bootstrap client. Calling the loader directly guarantees history
            //    lands in RoomStore BEFORE the user opens the Chat screen.
            try {
                val activeJid = com.ethora.chat.core.store.RoomStore.currentRoom.value?.jid
                MessageLoader.loadInitialMessagesForAllRooms(
                    xmppClient = client,
                    activeRoomJid = activeJid,
                    batchSize = 5,
                    messagesPerRoom = 30
                )
                android.util.Log.d(TAG, "Initial history preload complete (${com.ethora.chat.core.store.RoomStore.rooms.value.size} rooms)")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "loadInitialMessagesForAllRooms failed", e)
            }

            // 8. Background catchup with the delta/replace semantics. Quick no-op
            //    after a fresh preload (all rooms' anchors will be in the pages
            //    they just fetched), matters after suspend/reconnect.
            try {
                IncrementalHistoryLoader.updateMessagesTillLast(
                    xmppClient = client,
                    batchSize = 2,
                    messagesPerFetch = 20
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "updateMessagesTillLast failed", e)
            }

            lastBootstrapKey = key
            _isInitialized.value = true
            lastFailedKey = null
            lastFailedAtMs = 0L
            committedKey = true
            android.util.Log.d(TAG, "Bootstrap complete")
        } finally {
            if (!committedKey) {
                lastBootstrapKey = null
                lastFailedKey = key
                lastFailedAtMs = System.currentTimeMillis()
            }
        }
    }

    fun shutdown() {
        scope.launch { shutdownBlocking() }
    }

    /**
     * Suspending variant of [shutdown] used by [LogoutService] so the logout
     * flow can await the bootstrap teardown before firing the external
     * callback. Resets everything that would otherwise survive across a
     * logout → login boundary:
     *  • bootstrap key + initialized flag (lets the next user re-run the flow)
     *  • shared XMPP client (so we don't hand a disconnected instance to a
     *    fresh Chat composition)
     *  • InitBeforeLoadFlow guard (lets the private-store sync re-run even
     *    when the next XMPPClient happens to land on the same identity hash)
     *  • MessageLoader sync flag (lets the first-pass history preload fire
     *    for the freshly-logged-in user instead of downgrading to the delta
     *    catchup, which would silently leave the new user's rooms empty)
     *  • XMPPClientRegistry (disconnects + forgets the cached fallback client)
     */
    suspend fun shutdownBlocking() {
        bootstrapMutex.withLock {
            runCatching { _sharedXmppClient.value?.disconnect() }
                .onFailure { android.util.Log.w(TAG, "shared client disconnect threw", it) }
            _sharedXmppClient.value = null
            _isInitialized.value = false
            lastBootstrapKey = null
        }
        runCatching { com.ethora.chat.core.store.InitBeforeLoadFlow.reset() }
        runCatching { com.ethora.chat.core.store.MessageLoader.reset() }
        runCatching { com.ethora.chat.internal.XMPPClientRegistry.clear() }
    }

    private fun bootstrapKey(config: ChatConfig): String {
        val jwt = config.jwtLogin?.token ?: ""
        val xmppUser = config.userLogin?.user?.xmppUsername ?: ""
        return "$jwt|$xmppUser|${config.baseUrl ?: ""}|${config.xmppSettings?.host ?: ""}"
    }
}
