package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatEvent
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.User
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.networking.TokenManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.service.LogoutService
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.ChatConnectionStatus
import com.ethora.chat.core.store.ChatEventDispatcher
import com.ethora.chat.core.store.ConnectionStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageLoaderQueue
import com.ethora.chat.core.store.MessagePriorityQueue
import com.ethora.chat.core.store.PendingMediaSendQueue
import com.ethora.chat.core.store.IncrementalHistoryLoader
import com.ethora.chat.core.store.LogStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.store.buildPlaceholderRoom
import com.ethora.chat.core.store.findRoomByJid
import com.ethora.chat.core.store.normalizeRoomJid
import com.ethora.chat.core.store.toBareRoomName
import com.ethora.chat.core.push.PushNotificationManager
import com.ethora.chat.core.xmpp.XMPPClientDelegate
import com.ethora.chat.core.xmpp.ConnectionStatus
import com.ethora.chat.ui.components.ChatRoomView
import com.ethora.chat.ui.components.RoomListView
import com.ethora.chat.ui.styling.ChatTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main chat component entry point
 * Similar to ReduxWrapper in web version
 * 
 * @param config Chat configuration (mirrors web IConfig)
 * @param user Optional user to initialize with (mirrors web ChatWrapperProps.user)
 * @param roomJID Optional room JID for single room mode (mirrors web ChatWrapperProps.roomJID)
 * @param modifier Modifier for the composable
 */
@Composable
fun Chat(
    config: ChatConfig,
    user: User? = null,
    roomJID: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val localStorage = remember { LocalStorage(context) }

    ChatStore.setConfig(config)
    ChatStore.validateServerConfig(config)?.let { error ->
        ConnectionStore.setState(
            status = ChatConnectionStatus.ERROR,
            reason = error,
            isRecovering = false
        )
        return
    }
    PendingMediaSendQueue.initialize(context)

    // Set base URL and app token immediately (mirrors React: setBaseURL in LoginWrapper useEffect)
    // Must run before any API call - config values replace defaults project-wide
    val baseUrl = ChatStore.getEffectiveBaseUrl()
    ApiClient.setBaseUrl(baseUrl, config.customAppToken)

    // Auto-trigger initBeforeLoad bootstrap when the config asks for it.
    // Matches web's xmppProvider.tsx (L216-332) — there `useEffect` fires on
    // `config.initBeforeLoad` changes, runs the bootstrap, and caches it via
    // `completedInitBeforeLoadKeyRef`. Our bootstrap is already key-cached
    // inside `EthoraChatBootstrap`, so repeated calls are free.
    LaunchedEffect(config.initBeforeLoad, config.jwtLogin?.token, config.xmppSettings) {
        if (config.initBeforeLoad == true) {
            EthoraChatBootstrap.initialize(context, config)
        }
    }

    // Chat-area lifecycle: the moment the Chat composable leaves composition
    // (user switched away from the CHAT tab / navigated away from the whole
    // chat area), clear `_currentRoom`. Matches option Q13=c — nothing gets
    // cleared on a simple RoomListView ↔ ChatRoomView back-navigation (those
    // share this Chat composable), only when the user exits the chat area.
    // That makes the "active room" shortcut in updateUnreadCount wake up and
    // recompute unread for the freshly-backgrounded room.
    DisposableEffect(Unit) {
        onDispose {
            com.ethora.chat.core.store.RoomStore.setCurrentRoom(null)
            android.util.Log.d("EthoraChat", "Chat area left → setCurrentRoom(null)")
        }
    }

    LaunchedEffect(Unit) {
        if (user == null && UserStore.currentUser.value == null) {
            // 1) Load persisted user – show cached messages immediately with correct ownership
            val persistedUser = withContext(Dispatchers.IO) {
                UserStore.loadUserFromPersistence()
            }
            persistedUser?.let { u ->
                UserStore.setUser(u)
                ApiClient.setUserToken(u.token ?: "")
                android.util.Log.d("EthoraChat", "✅ Loaded persisted user (${u.xmppUsername ?: u.id})")
                return@LaunchedEffect
            }
            // Defer to EthoraChatBootstrap when it's enabled — it runs the
            // same login path under a mutex + cooldown.
            if (config.initBeforeLoad == true) {
                return@LaunchedEffect
            }
            val storedJWTToken = localStorage.getJWTToken()
            if (storedJWTToken != null) {
                android.util.Log.d("EthoraChat", "🔐 Attempting JWT login with stored token...")
                try {
                    val loginResponse = withContext(Dispatchers.IO) {
                        AuthAPIHelper.loginViaJWT(storedJWTToken)
                    }
                    loginResponse?.let { response ->
                        UserStore.setUser(response)
                        ApiClient.setUserToken(response.token)
                        localStorage.saveJWTToken(storedJWTToken)
                        TokenManager.startAutoRefresh()
                        android.util.Log.d("EthoraChat", "✅ JWT login successful")
                    } ?: run {
                        localStorage.clearJWTToken()
                        android.util.Log.w("EthoraChat", "⚠️ Stored JWT invalid, cleared")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EthoraChat", "❌ JWT login failed", e)
                    localStorage.clearJWTToken()
                }
            }
        }
    }
    
    // Initialize user - priority: direct user param > config.userLogin.user > JWT login > stored JWT > default login
    LaunchedEffect(user, config.userLogin, config.jwtLogin, config.defaultLogin) {
        val userLogin = config.userLogin
        val jwtLogin = config.jwtLogin
        
        when {
            // Direct user parameter (highest priority)
            user != null -> {
                UserStore.setUser(user)
            }
            // User login from config
            userLogin?.enabled == true -> {
                val loginUser = userLogin.user
                if (loginUser != null) {
                    UserStore.setUser(loginUser)
                }
            }
            // JWT login — only when bootstrap isn't handling it (dedup).
            jwtLogin?.enabled == true && config.initBeforeLoad != true -> {
                val token = jwtLogin.token
                if (token != null) {
                    val loginResponse = withContext(Dispatchers.IO) {
                        AuthAPIHelper.loginViaJWT(token)
                    }
                    loginResponse?.let { response ->
                        UserStore.setUser(response)
                        ApiClient.setUserToken(response.token)
                        localStorage.saveJWTToken(token)
                        TokenManager.startAutoRefresh()
                        android.util.Log.d("EthoraChat", "✅ JWT login successful (config)")
                    }
                }
            }
            // Default login (if enabled)
            config.defaultLogin == true -> {
                // Default login logic would go here
                // For now, we'll skip it as it requires email/password
            }
        }
    }
    
    // Get current user from store
    val currentUser by UserStore.currentUser.collectAsState()
    val requestedSingleRoomJid = roomJID ?: config.defaultRooms?.firstOrNull()?.jid
    val normalizedSingleRoomJid = remember(requestedSingleRoomJid, config.xmppSettings?.conference) {
        requestedSingleRoomJid?.let { normalizeRoomJid(it, config.xmppSettings?.conference) }
    }

    LaunchedEffect(normalizedSingleRoomJid, currentUser?.id) {
        val singleRoomJid = normalizedSingleRoomJid ?: return@LaunchedEffect
        val existing = RoomStore.getRoomByJid(singleRoomJid)
        if (existing == null) {
            val placeholder = buildPlaceholderRoom(
                normalizedRoomJid = singleRoomJid,
                originalRoomJid = requestedSingleRoomJid,
                titleOverride = resolveRoomTitle(singleRoomJid, requestedSingleRoomJid, config)
            )
            RoomStore.upsertRoom(placeholder)
            RoomStore.setCurrentRoom(placeholder)
            LogStore.info("EthoraChat", "Single-room placeholder created room=$singleRoomJid", category = "single-room")
        } else {
            RoomStore.setCurrentRoom(existing)
        }
    }
    
    // Load rooms (cache-first, then refresh from API) similar to chat-app/AuthManager.loadRooms().
    // Important: do NOT skip API refresh just because cache is non-empty (stale rooms issue).
    var didFetchRoomsFromApi by remember(currentUser?.id) { mutableStateOf(false) }
    LaunchedEffect(currentUser?.id, normalizedSingleRoomJid) {
        if (currentUser == null) return@LaunchedEffect
        if (didFetchRoomsFromApi) return@LaunchedEffect

        didFetchRoomsFromApi = true
        try {
            RoomStore.setLoading(true)

            // 1) Cache first for fast UI
            val existingRooms = RoomStore.rooms.value
            if (existingRooms.isEmpty()) {
                val cachedRooms = withContext(Dispatchers.IO) {
                    RoomStore.loadRoomsFromPersistence()
                }
                if (cachedRooms.isNotEmpty()) {
                    RoomStore.setRooms(cachedRooms)
                    android.util.Log.d("EthoraChat", "📦 Loaded ${cachedRooms.size} rooms from cache")
                }
            }

            // 2) Always refresh from API (chats/my)
            LogStore.info("EthoraChat", "Rooms sync start (GET /chats/my)", category = "api")
            val rooms = withContext(Dispatchers.IO) {
                RoomsAPIHelper.getRooms()
            }
            RoomStore.setRooms(rooms)
            LogStore.success(
                "EthoraChat",
                "Rooms sync result count=${rooms.size}",
                category = "api"
            )
            android.util.Log.d("EthoraChat", "✅ Loaded ${rooms.size} rooms from API")
            normalizedSingleRoomJid?.let { targetJid ->
                findRoomByJid(RoomStore.rooms.value, targetJid)?.let { resolved ->
                    RoomStore.setCurrentRoom(resolved)
                    LogStore.success("EthoraChat", "Single-room hydrated from API room=$targetJid", category = "single-room")
                } ?: LogStore.warning("EthoraChat", "Single-room still using placeholder after /chats/my room=$targetJid", category = "single-room")
            }
        } catch (e: Exception) {
            LogStore.error("EthoraChat", "Rooms sync failed: ${e.message}", category = "api")
            android.util.Log.e("EthoraChat", "❌ Failed to load rooms", e)
        } finally {
            RoomStore.setLoading(false)
        }
    }
    
    ChatTheme(colors = config.colors) {
        var hadSuccessfulConnection by remember(currentUser?.id) { mutableStateOf(false) }

        // Initialize XMPP client if user is available.
        // Reuse the instance from EthoraChatBootstrap if the host app already
        // ran preload — otherwise we'd open a second socket for the same JID
        // and the XMPP server typically kicks one, crashing/confusing the
        // other. Bootstrap ALWAYS creates the client for the current user, so
        // if both are non-null we can safely trust the match without a
        // substring hack on XMPPClient.toString().
        val bootstrappedClient by EthoraChatBootstrap.sharedXmppClient.collectAsState()
        val xmppClient = remember(currentUser?.xmppUsername, currentUser?.xmppPassword, config.xmppSettings, bootstrappedClient) {
            if (bootstrappedClient != null && currentUser?.xmppUsername != null) {
                // CRITICAL: the bootstrapped client was built with an EMPTY
                // delegate, so onComposingReceived / onMessageEdited / … all
                // no-op. The typing indicator, edit/reaction callbacks etc.
                // silently never reach the stores. Before returning this
                // instance we MUST swap in the full delegate below (see the
                // `LaunchedEffect(xmppClient)` that runs setDelegate). Here
                // we just hand back the inherited client.
                bootstrappedClient
            } else currentUser?.let { user ->
                user.xmppUsername?.let { username ->
                    user.xmppPassword?.let { password ->
                        android.util.Log.d("EthoraChat", "🏗️ Acquiring XMPPClient for $username via registry")
                        // Route the fallback (non-bootstrap) path through the
                        // process-wide XMPPClientRegistry. When the host app
                        // doesn't call EthoraChatBootstrap.initialize(), the
                        // Chat subtree can still be disposed/remounted by
                        // BottomNav or other host-side composition churn,
                        // and the `remember(...)` slot above would otherwise
                        // allocate a fresh XMPPClient per mount — racing
                        // multiple clients to SASL/bind the same JID and
                        // getting kicked by ejabberd. The registry keeps a
                        // single cached instance per username.
                        val client = com.ethora.chat.internal.XMPPClientRegistry.getOrCreate(
                            username = username,
                            password = password,
                            settings = config.xmppSettings,
                            dnsFallbackOverrides = config.dnsFallbackOverrides
                        )

                        // Set delegate — ONLY reports connection state to the
                        // ConnectionStore/dispatcher. History loading is driven
                        // from the `LaunchedEffect(xmppClient)` below, not from
                        // this delegate: the delegate's onXMPPClientConnected
                        // doesn't fire for clients inherited from
                        // EthoraChatBootstrap that are already online, and we
                        // can't afford to miss the initial load in that case.
                        client.setDelegate(object : XMPPClientDelegate {
                            override fun onXMPPClientConnected(client: XMPPClient) {
                                hadSuccessfulConnection = true
                                val state = com.ethora.chat.core.store.ChatConnectionState(
                                    status = ChatConnectionStatus.ONLINE,
                                    reason = null,
                                    isRecovering = false
                                )
                                ConnectionStore.setState(ChatConnectionStatus.ONLINE)
                                ChatEventDispatcher.emit(ChatEvent.ConnectionChanged(state))
                                // On genuine reconnects (not initial connect), fire a
                                // delta catchup to grab whatever we missed while offline.
                                // This path runs IN ADDITION to the LaunchedEffect-driven
                                // initial preload — guarded by MessageLoader.isSynced so
                                // the two don't double-fetch.
                                if (MessageLoader.isSynced()) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            delay(300)
                                            MessageLoader.syncMessagesSince(
                                                xmppClient = client,
                                                batchSize = 5,
                                                messagesPerRoom = 10
                                            )
                                        } catch (e: Exception) {
                                            android.util.Log.e("EthoraChat", "reconnect delta sync failed", e)
                                        }
                                    }
                                }
                            }
                            
                            override fun onXMPPClientDisconnected(client: XMPPClient) {
                                val state = com.ethora.chat.core.store.ChatConnectionState(
                                    status = ChatConnectionStatus.OFFLINE,
                                    reason = "Disconnected",
                                    isRecovering = hadSuccessfulConnection
                                )
                                ConnectionStore.setState(
                                    status = ChatConnectionStatus.OFFLINE,
                                    reason = "Disconnected",
                                    isRecovering = hadSuccessfulConnection
                                )
                                ChatEventDispatcher.emit(ChatEvent.ConnectionChanged(state))
                            }
                            
                            override fun onStatusChanged(client: XMPPClient, status: ConnectionStatus) {
                                val mapped = when (status) {
                                    ConnectionStatus.ONLINE -> ChatConnectionStatus.ONLINE
                                    ConnectionStatus.CONNECTING -> if (hadSuccessfulConnection) ChatConnectionStatus.DEGRADED else ChatConnectionStatus.CONNECTING
                                    ConnectionStatus.OFFLINE -> ChatConnectionStatus.OFFLINE
                                    ConnectionStatus.DISCONNECTING -> ChatConnectionStatus.DEGRADED
                                    ConnectionStatus.ERROR -> ChatConnectionStatus.ERROR
                                }
                                val isRecovering = mapped == ChatConnectionStatus.DEGRADED
                                val state = com.ethora.chat.core.store.ChatConnectionState(
                                    status = mapped,
                                    reason = status.name,
                                    isRecovering = isRecovering
                                )
                                ConnectionStore.setState(
                                    status = mapped,
                                    reason = status.name,
                                    isRecovering = isRecovering
                                )
                                ChatEventDispatcher.emit(ChatEvent.ConnectionChanged(state))
                            }
                            
                            override fun onMessageReceived(client: XMPPClient, message: com.ethora.chat.core.models.Message) {
                                // Messages are handled automatically by XMPPClient
                            }
                            
                            override fun onStanzaReceived(client: XMPPClient, stanza: com.ethora.chat.core.xmpp.XMPPStanza) {
                                // Stanzas are handled automatically by XMPPClient
                            }
                            
                            override fun onComposingReceived(client: XMPPClient, roomJid: String, isComposing: Boolean, composingList: List<String>) {
                                // Update composing state in RoomStore
                                com.ethora.chat.core.store.RoomStore.setComposing(roomJid, isComposing, composingList)
                            }
                            
                            override fun onMessageEdited(client: XMPPClient, roomJid: String, messageId: String, newText: String) {
                                com.ethora.chat.core.store.MessageStore.editMessage(roomJid, messageId, newText)
                            }
                            
                            override fun onReactionReceived(client: XMPPClient, roomJid: String, messageId: String, from: String, reactions: List<String>, data: Map<String, String>) {
                                // Reactions are handled automatically by XMPPClient
                            }
                        })
                        
                        LogoutService.setXMPPClient(client)
                        client
                    }
                }
            }
        }

        // Delegate rewire — unconditional. When `xmppClient` came from
        // EthoraChatBootstrap it was constructed with an empty delegate, so
        // onComposingReceived / onMessageEdited / etc. are no-ops. Even when
        // Chat constructed the client itself, re-applying is idempotent and
        // covers the case where `currentUser` / `config` change across
        // recompositions and we want the delegate closure to capture the
        // current `hadSuccessfulConnection` reference.
        LaunchedEffect(xmppClient) {
            val target = xmppClient ?: return@LaunchedEffect
            target.setDelegate(object : XMPPClientDelegate {
                override fun onXMPPClientConnected(client: XMPPClient) {
                    hadSuccessfulConnection = true
                    val state = com.ethora.chat.core.store.ChatConnectionState(
                        status = ChatConnectionStatus.ONLINE,
                        reason = null,
                        isRecovering = false
                    )
                    ConnectionStore.setState(ChatConnectionStatus.ONLINE)
                    ChatEventDispatcher.emit(ChatEvent.ConnectionChanged(state))
                    if (MessageLoader.isSynced()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                delay(300)
                                MessageLoader.syncMessagesSince(
                                    xmppClient = client,
                                    batchSize = 5,
                                    messagesPerRoom = 10
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("EthoraChat", "reconnect delta sync failed", e)
                            }
                        }
                    }
                }

                override fun onXMPPClientDisconnected(client: XMPPClient) {
                    val state = com.ethora.chat.core.store.ChatConnectionState(
                        status = ChatConnectionStatus.OFFLINE,
                        reason = "Disconnected",
                        isRecovering = hadSuccessfulConnection
                    )
                    ConnectionStore.setState(
                        status = ChatConnectionStatus.OFFLINE,
                        reason = "Disconnected",
                        isRecovering = hadSuccessfulConnection
                    )
                    ChatEventDispatcher.emit(ChatEvent.ConnectionChanged(state))
                }

                override fun onStatusChanged(client: XMPPClient, status: ConnectionStatus) {
                    val mapped = when (status) {
                        ConnectionStatus.ONLINE -> ChatConnectionStatus.ONLINE
                        ConnectionStatus.CONNECTING -> if (hadSuccessfulConnection) ChatConnectionStatus.DEGRADED else ChatConnectionStatus.CONNECTING
                        ConnectionStatus.OFFLINE -> ChatConnectionStatus.OFFLINE
                        ConnectionStatus.DISCONNECTING -> ChatConnectionStatus.DEGRADED
                        ConnectionStatus.ERROR -> ChatConnectionStatus.ERROR
                    }
                    val isRecovering = mapped == ChatConnectionStatus.DEGRADED
                    val state = com.ethora.chat.core.store.ChatConnectionState(
                        status = mapped,
                        reason = status.name,
                        isRecovering = isRecovering
                    )
                    ConnectionStore.setState(
                        status = mapped,
                        reason = status.name,
                        isRecovering = isRecovering
                    )
                    ChatEventDispatcher.emit(ChatEvent.ConnectionChanged(state))
                }

                override fun onMessageReceived(client: XMPPClient, message: com.ethora.chat.core.models.Message) {}
                override fun onStanzaReceived(client: XMPPClient, stanza: com.ethora.chat.core.xmpp.XMPPStanza) {}
                override fun onComposingReceived(
                    client: XMPPClient, roomJid: String, isComposing: Boolean, composingList: List<String>
                ) {
                    com.ethora.chat.core.store.RoomStore.setComposing(roomJid, isComposing, composingList)
                }
                override fun onMessageEdited(client: XMPPClient, roomJid: String, messageId: String, newText: String) {
                    com.ethora.chat.core.store.MessageStore.editMessage(roomJid, messageId, newText)
                }
                override fun onReactionReceived(
                    client: XMPPClient, roomJid: String, messageId: String, from: String,
                    reactions: List<String>, data: Map<String, String>
                ) {}
            })
            LogoutService.setXMPPClient(target)
            android.util.Log.d("EthoraChat", "✅ Delegate wired on XMPPClient")
        }

        // Connect XMPP client
        LaunchedEffect(xmppClient) {
            if (xmppClient != null) {
                ConnectionStore.setReconnectAction {
                    ConnectionStore.setState(
                        status = ChatConnectionStatus.CONNECTING,
                        reason = "Manual reconnect",
                        isRecovering = false
                    )
                    xmppClient.initializeClient()
                }
                ConnectionStore.setState(
                    status = if (hadSuccessfulConnection) ChatConnectionStatus.DEGRADED else ChatConnectionStatus.CONNECTING,
                    reason = "Initializing XMPP client",
                    isRecovering = hadSuccessfulConnection
                )
                xmppClient.initializeClient()
            } else {
                ConnectionStore.setState(
                    status = ChatConnectionStatus.OFFLINE,
                    reason = "XMPP client unavailable"
                )
                ConnectionStore.setReconnectAction(null)
            }
        }

        DisposableEffect(lifecycleOwner, xmppClient) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                    xmppClient?.let { client ->
                        client.resetReconnectAttempts("app foreground")
                        if (!client.isFullyConnected() && !client.checkConnecting()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                ConnectionStore.setState(
                                    status = ChatConnectionStatus.CONNECTING,
                                    reason = "App foreground reconnect",
                                    isRecovering = hadSuccessfulConnection
                                )
                                client.initializeClient()
                            }
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        
        // Message loader queue (for background loading after initial load)
        // Matches web: useMessageLoaderQueue hook
        val messageLoaderQueue = remember(xmppClient) {
            xmppClient?.let { MessageLoaderQueue(it) }
        }

        val currentRoom by RoomStore.currentRoom.collectAsState()
        
        // Message priority queue (for prioritized loading)
        // Matches web: useMessageQueue hook
        val messagePriorityQueue = remember(xmppClient, currentRoom?.jid) {
            xmppClient?.let { 
                MessagePriorityQueue(it, currentRoom?.jid)
            }
        }
        
        // Subscribe to rooms so we recompose when they load (fix: LaunchedEffect was not re-running when rooms were set async)
        val rooms by RoomStore.rooms.collectAsState(initial = emptyList())

        LaunchedEffect(xmppClient, normalizedSingleRoomJid, rooms.size) {
            val client = xmppClient ?: return@LaunchedEffect
            val singleRoomJid = normalizedSingleRoomJid ?: return@LaunchedEffect
            try {
                LogStore.info("EthoraChat", "Single-room bootstrap start room=$singleRoomJid", category = "single-room")
                if (!client.ensureConnected(timeoutMs = 5_000)) {
                    LogStore.warning("EthoraChat", "Single-room bootstrap skipped, XMPP not connected room=$singleRoomJid", category = "single-room")
                    return@LaunchedEffect
                }

                val joined = client.ensureRoomPresence(
                    roomJID = singleRoomJid,
                    timeoutMs = 1_200,
                    waitForJoin = true,
                    source = "single_room_bootstrap"
                )
                if (!joined) {
                    client.ensureRoomPresence(
                        roomJID = singleRoomJid,
                        timeoutMs = 900,
                        waitForJoin = false,
                        source = "single_room_bootstrap_background"
                    )
                }

                val refreshedRooms = withContext(Dispatchers.IO) {
                    runCatching { RoomsAPIHelper.getRooms() }.getOrNull()
                }
                if (!refreshedRooms.isNullOrEmpty()) {
                    RoomStore.setRooms(refreshedRooms)
                }

                val resolvedRoom = findRoomByJid(RoomStore.rooms.value, singleRoomJid)
                    ?: RoomStore.getRoomByJid(singleRoomJid)
                resolvedRoom?.let { room ->
                    RoomStore.setCurrentRoom(room)
                    if ((com.ethora.chat.core.store.MessageStore.getMessagesForRoom(room.jid)).isEmpty()) {
                        RoomStore.setRoomLoading(room.jid, true)
                        LogStore.info(
                            "EthoraChat",
                            "Single-room history query start room=${room.jid} max=30",
                            category = "history"
                        )
                        // Plan A6: bounded loader with hard-cap, mirrors web ACTIVE_ROOM_LOADER_HARD_CAP_MS=3000.
                        val hardCap = launch {
                            delay(3_000)
                            RoomStore.setRoomLoading(room.jid, false)
                            LogStore.warning(
                                "EthoraChat",
                                "Single-room history loader hard-cap fired room=${room.jid}",
                                category = "history"
                            )
                        }
                        try {
                            val history = client.getHistory(room.jid, max = 30, beforeMessageId = null)
                            if (history.isNotEmpty()) {
                                com.ethora.chat.core.store.MessageStore.addMessages(room.jid, history)
                                LogStore.success("EthoraChat", "Single-room history loaded room=${room.jid} count=${history.size}", category = "history")
                            } else {
                                LogStore.info("EthoraChat", "Single-room history empty room=${room.jid}", category = "history")
                            }
                        } catch (e: Exception) {
                            LogStore.error(
                                "EthoraChat",
                                "Single-room history query error room=${room.jid} error=${e.message}",
                                category = "history"
                            )
                            throw e
                        } finally {
                            hardCap.cancel()
                            RoomStore.setRoomLoading(room.jid, false)
                        }
                    }
                }
                LogStore.success("EthoraChat", "Single-room bootstrap complete room=$singleRoomJid", category = "single-room")
            } catch (e: Exception) {
                LogStore.error("EthoraChat", "Single-room bootstrap failed room=$singleRoomJid error=${e.message}", category = "single-room")
            }
        }
        
        // Load initial messages once XMPP is fully connected (avoids "Cannot get history: not fully connected")
        LaunchedEffect(xmppClient, rooms.size) {
            val client = xmppClient
            val roomsList = RoomStore.rooms.value
            
            if (client != null && roomsList.isNotEmpty()) {
                try {
                    if (!client.ensureConnected(timeoutMs = 5000)) {
                        android.util.Log.w("EthoraChat", "XMPP not ready within 5s, skipping initial message load for now")
                        return@LaunchedEffect
                    }
                    
                    val activeRoomJid = currentRoom?.jid
                    MessageLoader.loadInitialMessagesForAllRooms(
                        xmppClient = client,
                        activeRoomJid = activeRoomJid,
                        batchSize = 5,
                        messagesPerRoom = 30
                    )
                    
                    messageLoaderQueue?.start()
                    
                    messagePriorityQueue?.let { queue ->
                        queue.initialize(roomsList)
                        queue.start()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("EthoraChat", "Error loading initial messages", e)
                }
            }
        }
        
        DisposableEffect(xmppClient) {
            onDispose {
                android.util.Log.d("EthoraChat", "🧹 Disposing XMPP components")
                messageLoaderQueue?.stop()
                messagePriorityQueue?.stop()
                xmppClient?.disconnect()
                LogoutService.setXMPPClient(null)
                ConnectionStore.setReconnectAction(null)
                ConnectionStore.setState(
                    status = ChatConnectionStatus.OFFLINE,
                    reason = "Disposed"
                )
            }
        }
        
        LaunchedEffect(xmppClient) {
            val client = xmppClient ?: return@LaunchedEffect

            // Fallback for hosts that mount Chat without EthoraChatProvider.
            // When bootstrap already ran these stages, skip.
            if (EthoraChatBootstrap.isInitialized.value) {
                return@LaunchedEffect
            }

            // Wait until the socket finishes auth + bind. Prior to this, the
            // delegate's onXMPPClientConnected WOULD have been the trigger, but
            // that handler never fires when Chat inherits an already-connected
            // shared client from EthoraChatBootstrap — so we drive the load
            // from here instead, which works for both "Chat created the client"
            // and "Chat inherited a bootstrap client" cases.
            if (!client.isFullyConnected()) {
                client.ensureConnected(10_000)
            }
            if (!client.isFullyConnected()) {
                android.util.Log.w("EthoraChat", "XMPP not online yet; post-connect tasks deferred")
                return@LaunchedEffect
            }

            // 1. Private-store chatjson → per-room lastViewedTimestamp sync.
            try {
                com.ethora.chat.core.store.InitBeforeLoadFlow.run(client)
            } catch (e: Exception) {
                android.util.Log.e("EthoraChat", "initBeforeLoad flow error", e)
            }

            // 2. Initial history preload for every room, EXCEPT if something
            //    already ran it (MessageLoader.isSynced()), in which case we
            //    downgrade to the lightweight delta catchup — same policy web
            //    follows with its historyPreloadScheduler vs updateMessagesTillLast.
            try {
                if (!MessageLoader.isSynced()) {
                    android.util.Log.d("EthoraChat", "Running loadInitialMessagesForAllRooms")
                    MessageLoader.loadInitialMessagesForAllRooms(
                        xmppClient = client,
                        activeRoomJid = RoomStore.currentRoom.value?.jid,
                        batchSize = 5,
                        messagesPerRoom = 30
                    )
                } else {
                    android.util.Log.d("EthoraChat", "Already synced → delta catchup only")
                    IncrementalHistoryLoader.updateMessagesTillLast(
                        xmppClient = client,
                        batchSize = 2,
                        messagesPerFetch = 20
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("EthoraChat", "Error in history preload/catchup", e)
            }
        }
        
        // Push subscribe paths gated behind SDK_PUSH_SUBSCRIBE_ENABLED
        // (see const at end of file). FCM token + pending-JID deep-link stay live.
        val fcmToken by PushNotificationManager.fcmToken.collectAsState()
        if (SDK_PUSH_SUBSCRIBE_ENABLED) {
            LaunchedEffect(fcmToken, currentUser?.walletAddress) {
                val token = fcmToken
                if (token != null && currentUser != null) {
                    android.util.Log.d("EthoraChat", "🔔 Push: subscribing to backend (token=${token.take(10)}...)")
                    withContext(Dispatchers.IO) {
                        PushNotificationManager.subscribeToBackend(token)
                    }
                }
            }

            LaunchedEffect(xmppClient, rooms.size, fcmToken) {
                val client = xmppClient
                val roomsList = RoomStore.rooms.value
                if (client != null && roomsList.isNotEmpty()) {
                    if (client.ensureConnected(timeoutMs = 5000)) {
                        android.util.Log.d("EthoraChat", "🔔 Push: subscribing to ${roomsList.size} rooms via MUC-SUB")
                        withContext(Dispatchers.IO) {
                            PushNotificationManager.subscribeToRooms(
                                client,
                                roomsList.map { it.jid }
                            )
                        }
                    } else {
                        android.util.Log.w("EthoraChat", "🔔 Push: XMPP not ready within 5s, skipping MUC-SUB for now")
                    }
                }
            }
        }
        
        // Push: handle pending notification JID → open the room
        val pendingJid by PushNotificationManager.pendingNotificationJid.collectAsState()
        var selectedRoom by remember { mutableStateOf<com.ethora.chat.core.models.Room?>(null) }
        
        LaunchedEffect(pendingJid, rooms.size) {
            val jid = pendingJid
            if (jid != null && rooms.isNotEmpty()) {
                val room = rooms.find { it.jid == jid }
                if (room != null) {
                    android.util.Log.d("EthoraChat", "Opening room from push notification: $jid")
                    RoomStore.setCurrentRoom(room)
                    selectedRoom = room
                    PushNotificationManager.clearPendingNotificationJid()
                }
            }
        }

        // Show room list or single room based on config
        if (config.disableRooms == true) {
            val roomLoadingStates by RoomStore.roomLoadingStates.collectAsState(initial = emptyMap())
            val roomsLoading by RoomStore.isLoading.collectAsState()
            val connectionState by ConnectionStore.state.collectAsState()

            // If roomJID/defaultRooms not provided, pick the first room returned by /chats/my.
            // Also re-select if current selection disappears after a refresh.
            LaunchedEffect(rooms.size, requestedSingleRoomJid) {
                if (requestedSingleRoomJid == null && rooms.isNotEmpty()) {
                    val current = RoomStore.currentRoom.value
                    val currentExists = current != null && rooms.any { it.jid == current.jid }
                    if (!currentExists) {
                        RoomStore.setCurrentRoom(rooms.first())
                    }
                }
            }

            val targetRoom = remember(rooms, normalizedSingleRoomJid) {
                normalizedSingleRoomJid?.let { jid -> findRoomByJid(rooms, jid) }
            }

            val roomToRender = targetRoom ?: run {
                if (requestedSingleRoomJid == null) RoomStore.currentRoom.value else RoomStore.getRoomByJid(normalizedSingleRoomJid ?: "")
            }

            LaunchedEffect(roomToRender?.jid) {
                if (roomToRender != null) {
                    RoomStore.setCurrentRoom(roomToRender)
                }
            }

            val roomPhase = when {
                roomToRender != null && roomLoadingStates[roomToRender.jid] == true -> RoomConnectionPhase.LOADING_HISTORY
                roomToRender != null -> RoomConnectionPhase.READY
                requestedSingleRoomJid == null && roomsLoading -> RoomConnectionPhase.LOADING_ROOMS
                normalizedSingleRoomJid != null && roomsLoading -> RoomConnectionPhase.LOADING_ROOMS
                connectionState.status == ChatConnectionStatus.CONNECTING ||
                    connectionState.status == ChatConnectionStatus.DEGRADED -> RoomConnectionPhase.CONNECTING_XMPP
                normalizedSingleRoomJid != null && connectionState.status == ChatConnectionStatus.ERROR -> RoomConnectionPhase.FAILED
                normalizedSingleRoomJid != null && rooms.isNotEmpty() && connectionState.status == ChatConnectionStatus.ONLINE && roomToRender == null -> RoomConnectionPhase.FAILED
                else -> RoomConnectionPhase.RESOLVING_ROOM
            }

            if (roomToRender != null) {
                ChatRoomView(
                    room = roomToRender,
                    xmppClient = xmppClient,
                    onBack = { /* Single-room mode: back is controlled by host app */ },
                    modifier = modifier
                )
            } else {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hasRequestedRoom = normalizedSingleRoomJid != null
                    when {
                        !hasRequestedRoom -> Text("No room configured")
                        roomPhase != RoomConnectionPhase.FAILED -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = when (roomPhase) {
                                        RoomConnectionPhase.CONNECTING_XMPP -> "Connecting to room..."
                                        RoomConnectionPhase.LOADING_HISTORY -> "Loading messages..."
                                        else -> "Loading room..."
                                    }
                                )
                            }
                        }
                        else -> Text("Room unavailable or access denied")
                    }
                }
            }
        } else {
            if (selectedRoom != null) {
                ChatRoomView(
                    room = selectedRoom!!,
                    xmppClient = xmppClient,
                    onBack = { 
                        selectedRoom?.let { room ->
                            com.ethora.chat.core.store.RoomStore.setLastViewedTimestamp(room.jid, System.currentTimeMillis())
                        }
                        com.ethora.chat.core.store.RoomStore.setCurrentRoom(null)
                        selectedRoom = null 
                    },
                    modifier = modifier
                )
            } else {
                RoomListView(
                    onRoomSelected = { room -> selectedRoom = room },
                    modifier = modifier
                )
            }
        }
    }
}

private enum class RoomConnectionPhase {
    RESOLVING_ROOM,
    LOADING_ROOMS,
    CONNECTING_XMPP,
    LOADING_HISTORY,
    READY,
    FAILED
}

// Push subscription (backend POST + MUC-SUB per room) is off for now.
// Flip to true to re-enable.
private const val SDK_PUSH_SUBSCRIBE_ENABLED: Boolean = false

private fun resolveRoomTitle(
    normalizedRoomJid: String,
    originalRoomJid: String?,
    config: ChatConfig
): String {
    val bare = toBareRoomName(normalizedRoomJid)
    val overrides = config.chatHeaderSettings?.roomTitleOverrides ?: emptyMap()
    return overrides[normalizedRoomJid]
        ?: overrides[bare]
        ?: originalRoomJid?.let { overrides[it] }
        ?: bare
}
