package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.ethora.chat.core.config.ChatConfig
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
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageLoaderQueue
import com.ethora.chat.core.store.MessagePriorityQueue
import com.ethora.chat.core.store.IncrementalHistoryLoader
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.push.PushNotificationManager
import com.ethora.chat.core.xmpp.XMPPClientDelegate
import com.ethora.chat.core.xmpp.ConnectionStatus
import com.ethora.chat.ui.components.ChatRoomView
import com.ethora.chat.ui.components.RoomListView
import com.ethora.chat.ui.styling.ChatTheme
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
    val localStorage = remember { LocalStorage(context) }
    
    ChatStore.setConfig(config)

    // Set base URL and app token immediately (mirrors React: setBaseURL in LoginWrapper useEffect)
    // Must run before any API call - config values replace defaults project-wide
    val baseUrl = config.baseUrl ?: com.ethora.chat.core.config.AppConfig.defaultBaseURL
    ApiClient.setBaseUrl(baseUrl, config.customAppToken)

    // Persistence first (preshent-mobile style): load persisted user, then JWT only if needed
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
            // 2) No persisted user – try stored JWT token
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
            // JWT login from config
            jwtLogin?.enabled == true -> {
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
    
    // Load rooms once globally (similar to web: if (roomsList && Object.keys(roomsList).length > 0))
    LaunchedEffect(currentUser) {
        val existingRooms = RoomStore.rooms.value
        if (currentUser != null && existingRooms.isEmpty()) {
            try {
                RoomStore.setLoading(true)

                // 1) Load cached rooms first for fast UI (shows last message immediately)
                val cachedRooms = withContext(Dispatchers.IO) {
                    RoomStore.loadRoomsFromPersistence()
                }
                if (cachedRooms.isNotEmpty()) {
                    RoomStore.setRooms(cachedRooms)
                    android.util.Log.d("EthoraChat", "📦 Loaded ${cachedRooms.size} rooms from cache")
                }

                // 2) Fetch fresh rooms from API and replace cache
                val rooms = withContext(Dispatchers.IO) {
                    RoomsAPIHelper.getRooms()
                }
                RoomStore.setRooms(rooms)
                android.util.Log.d("EthoraChat", "✅ Loaded ${rooms.size} rooms from API")
            } catch (e: Exception) {
                android.util.Log.e("EthoraChat", "❌ Failed to load rooms", e)
            } finally {
                RoomStore.setLoading(false)
            }
        } else if (existingRooms.isNotEmpty()) {
            android.util.Log.d("EthoraChat", "⏭️ Rooms already loaded (${existingRooms.size} rooms), skipping API request")
            RoomStore.setLoading(false)
        }
    }
    
    ChatTheme(colors = config.colors) {
        // Initialize XMPP client if user is available
        // Key by essential XMPP credentials to avoid recreation on minor user updates
        val xmppClient = remember(currentUser?.xmppUsername, currentUser?.xmppPassword, config.xmppSettings) {
            currentUser?.let { user ->
                user.xmppUsername?.let { username ->
                    user.xmppPassword?.let { password ->
                        android.util.Log.d("EthoraChat", "🏗️ Creating new XMPPClient for $username")
                        val client = XMPPClient(
                            username = username,
                            password = password,
                            settings = config.xmppSettings,
                            dnsFallbackOverrides = config.dnsFallbackOverrides
                        )
                        
                        // Set delegate to handle reconnection and sync
                        client.setDelegate(object : XMPPClientDelegate {
                            override fun onXMPPClientConnected(client: XMPPClient) {
                                // Sync messages when reconnected (after offline)
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        delay(2000) // Wait for XMPP to be fully ready
                                        if (!MessageLoader.isSynced()) {
                                            // Initial sync; matches web: getHistoryStanza(chat.jid, 30)
                                            val activeRoomJid = RoomStore.currentRoom.value?.jid
                                            MessageLoader.loadInitialMessagesForAllRooms(
                                                xmppClient = client,
                                                activeRoomJid = activeRoomJid,
                                                batchSize = 5,
                                                messagesPerRoom = 30
                                            )
                                        } else {
                                            // Incremental sync after reconnect
                                            MessageLoader.syncMessagesSince(
                                                xmppClient = client,
                                                batchSize = 5,
                                                messagesPerRoom = 10
                                            )
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("EthoraChat", "Error syncing messages on reconnect", e)
                                    }
                                }
                            }
                            
                            override fun onXMPPClientDisconnected(client: XMPPClient) {
                                // Handle disconnection if needed
                            }
                            
                            override fun onStatusChanged(client: XMPPClient, status: ConnectionStatus) {
                                // Handle status changes if needed
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
        
        // Connect XMPP client
        LaunchedEffect(xmppClient) {
            xmppClient?.initializeClient()
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
        
        // Load initial messages once XMPP is fully connected (avoids "Cannot get history: not fully connected")
        LaunchedEffect(xmppClient, rooms.size) {
            val client = xmppClient
            val roomsList = RoomStore.rooms.value
            
            if (client != null && roomsList.isNotEmpty()) {
                try {
                    // Wait for XMPP to be fully connected (up to 30s) before loading history
                    var waited = 0L
                    while (!client.isFullyConnected() && waited < 30000) {
                        delay(500)
                        waited += 500
                    }
                    if (!client.isFullyConnected()) {
                        android.util.Log.w("EthoraChat", "XMPP not fully connected after 30s, skipping message load")
                        return@LaunchedEffect
                    }
                    delay(150)
                    
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
            }
        }
        
        LaunchedEffect(xmppClient) {
            val client = xmppClient
            if (client != null && MessageLoader.isSynced()) {
                // Only run incremental sync if initial sync is complete
                // This handles offline recovery
                try {
                    IncrementalHistoryLoader.updateMessagesTillLast(
                        xmppClient = client,
                        batchSize = 5,
                        maxFetchAttempts = 4,
                        messagesPerFetch = 5
                    )
                } catch (e: Exception) {
                    android.util.Log.e("EthoraChat", "Error in incremental history sync", e)
                }
            }
        }
        
        // Push: subscribe device to backend when FCM token and user are available
        val fcmToken by PushNotificationManager.fcmToken.collectAsState()
        LaunchedEffect(fcmToken, currentUser?.walletAddress) {
            val token = fcmToken
            if (token != null && currentUser != null) {
                android.util.Log.d("EthoraChat", "🔔 Push: subscribing to backend (token=${token.take(10)}...)")
                withContext(Dispatchers.IO) {
                    PushNotificationManager.subscribeToBackend(token)
                }
            }
        }
        
        // Push: subscribe to all rooms via MUC-SUB after XMPP connected and rooms loaded
        LaunchedEffect(xmppClient, rooms.size, fcmToken) {
            val client = xmppClient
            val roomsList = RoomStore.rooms.value
            if (client != null && roomsList.isNotEmpty()) {
                // Wait for XMPP to be fully connected (up to 30s)
                var waited = 0L
                while (!client.isFullyConnected() && waited < 30000) {
                    delay(500)
                    waited += 500
                }
                if (client.isFullyConnected()) {
                    android.util.Log.d("EthoraChat", "🔔 Push: subscribing to ${roomsList.size} rooms via MUC-SUB")
                    withContext(Dispatchers.IO) {
                        PushNotificationManager.subscribeToRooms(
                            client,
                            roomsList.map { it.jid }
                        )
                    }
                } else {
                    android.util.Log.w("EthoraChat", "🔔 Push: XMPP not fully connected after 30s, skipping MUC-SUB")
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
            val requestedSingleRoomJid = roomJID ?: config.defaultRooms?.firstOrNull()?.jid
            val normalizedSingleRoomJid = remember(requestedSingleRoomJid, config.xmppSettings?.conference) {
                requestedSingleRoomJid?.let { normalizeRoomJid(it, config.xmppSettings?.conference) }
            }

            val targetRoom = remember(rooms, normalizedSingleRoomJid) {
                normalizedSingleRoomJid?.let { jid ->
                    findRoomByJid(RoomStore.rooms.value, jid)
                }
            }

            val fallbackRoom = remember(normalizedSingleRoomJid, requestedSingleRoomJid, config.chatHeaderSettings) {
                normalizedSingleRoomJid?.let { jid ->
                    Room(
                        id = jid.substringBefore("@"),
                        jid = jid,
                        name = resolveRoomTitle(jid, requestedSingleRoomJid, config),
                        title = resolveRoomTitle(jid, requestedSingleRoomJid, config)
                    )
                }
            }

            val roomToRender = targetRoom ?: fallbackRoom

            LaunchedEffect(roomToRender?.jid) {
                roomToRender?.let { room ->
                    if (RoomStore.getRoomByJid(room.jid) == null) {
                        RoomStore.addRoom(room)
                    }
                    RoomStore.setCurrentRoom(room)
                }
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
                    Text("No room configured")
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

private fun normalizeRoomJid(inputJid: String, conferenceDomain: String?): String {
    if (inputJid.contains("@")) return inputJid
    if (conferenceDomain.isNullOrBlank()) return inputJid
    return "$inputJid@$conferenceDomain"
}

private fun toBareRoomName(jid: String): String = jid.substringBefore("@")

private fun findRoomByJid(rooms: List<Room>, targetJid: String): Room? {
    return rooms.firstOrNull { room ->
        room.jid == targetJid || toBareRoomName(room.jid) == toBareRoomName(targetJid)
    }
}

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
