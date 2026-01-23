package com.ethora.chat.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatColors
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.config.UserLoginConfig
import com.ethora.chat.core.models.User
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.service.LogoutService
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.core.xmpp.XMPPClientDelegate
import com.ethora.chat.core.xmpp.ConnectionStatus
import com.ethora.chat.ui.styling.ChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var xmppClient: XMPPClient? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Test credentials (same as Swift version)
        val email = "yukiraze9@gmail.com"
        val password = "Qwerty123"

        setContent {
            ChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isLoading by remember { mutableStateOf(true) }
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    var connectionStatus by remember { mutableStateOf("Initializing...") }
                    var roomsCount by remember { mutableStateOf(0) }
                    var showChat by remember { mutableStateOf(false) }

                    // Initialize and test
                    LaunchedEffect(Unit) {
                        try {
                            Log.d(TAG, "🚀 Starting XMPP Chat Test App")
                            Log.d(TAG, "📧 Email: $email")
                            
                            // Initialize persistence managers
                            Log.d(TAG, "💾 Initializing persistence...")
                            val persistenceManager = ChatPersistenceManager(this@MainActivity)
                            val chatDatabase = ChatDatabase.getDatabase(this@MainActivity)
                            val messageCache = MessageCache(chatDatabase)
                            
                            // Initialize stores with persistence
                            RoomStore.initialize(persistenceManager)
                            UserStore.initialize(persistenceManager)
                            MessageStore.initialize(messageCache)
                            ScrollPositionStore.initialize(this@MainActivity)
                            
                            Log.d(TAG, "✅ Persistence initialized")
                            
                            // Load persisted data
                            Log.d(TAG, "📂 Loading persisted data...")
                            withContext(Dispatchers.IO) {
                                // Load user
                                val persistedUser = UserStore.loadUserFromPersistence()
                                if (persistedUser != null) {
                                    Log.d(TAG, "📂 Found persisted user, using it")
                                    withContext(Dispatchers.Main) {
                                        UserStore.setUser(persistedUser)
                                        ApiClient.setUserToken(persistedUser.token ?: "")
                                    }
                                }
                                
                                // Load rooms
                                val persistedRooms = RoomStore.loadRoomsFromPersistence()
                                if (persistedRooms.isNotEmpty()) {
                                    Log.d(TAG, "📂 Found ${persistedRooms.size} persisted rooms")
                                    withContext(Dispatchers.Main) {
                                        RoomStore.setRooms(persistedRooms)
                                        roomsCount = persistedRooms.size
                                        
                                        // Load messages for each room from persistence
                                        persistedRooms.forEach { room ->
                                            val messages = MessageStore.loadMessagesFromPersistence(room.jid)
                                            if (messages.isNotEmpty()) {
                                                MessageStore.setMessagesForRoom(room.jid, messages)
                                                Log.d(TAG, "📂 Loaded ${messages.size} persisted messages for ${room.jid}")
                                            }
                                        }
                                        
                                        // Load current room JID
                                        val currentRoomJid = RoomStore.loadCurrentRoomJidFromPersistence()
                                        if (currentRoomJid != null) {
                                            val currentRoom = persistedRooms.firstOrNull { it.jid == currentRoomJid }
                                            if (currentRoom != null) {
                                                RoomStore.setCurrentRoom(currentRoom)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Step 1: Login (only if no persisted user)
                            val currentUser = UserStore.currentUser.value
                            if (currentUser == null) {
                                connectionStatus = "Step 1: Logging in..."
                                Log.d(TAG, "🔐 Step 1: Logging in with email...")
                                
                                val loginResponse = AuthAPIHelper.loginWithEmail(email, password)
                                
                                // Save to UserStore
                                UserStore.setUser(loginResponse)
                                
                                // Set token in API client
                                ApiClient.setUserToken(loginResponse.token)
                                
                                Log.d(TAG, "✅ Login successful! User stored in UserStore")
                                connectionStatus = "Login successful!"
                            } else {
                                Log.d(TAG, "✅ Using persisted user, skipping login")
                                connectionStatus = "Using persisted user"
                            }
                            
                            // Step 2: Connect XMPP
                            connectionStatus = "Step 2: Connecting to XMPP server..."
                            Log.d(TAG, "🔐 Step 2: Connecting to XMPP server...")
                            
                            val userForXmpp = UserStore.currentUser.value
                            if (userForXmpp == null) {
                                throw Exception("User not found in store")
                            }
                            val xmppUsername = userForXmpp.xmppUsername ?: email
                            val xmppPassword = userForXmpp.xmppPassword ?: password
                            
                            val settings = XMPPSettings(
                                devServer = "wss://xmpp.ethoradev.com:5443/ws",
                                host = "xmpp.ethoradev.com",
                                conference = "conference.xmpp.ethoradev.com"
                            )
                            
                            xmppClient = XMPPClient(
                                username = xmppUsername,
                                password = xmppPassword,
                                settings = settings
                            )
                            
                            // Set XMPP client in LogoutService so external apps can logout
                            LogoutService.setXMPPClient(xmppClient)
                            
                            // Set delegate
                            xmppClient?.setDelegate(object : XMPPClientDelegate {
                                override fun onXMPPClientConnected(client: XMPPClient) {
                                    Log.d(TAG, "✅ XMPP Client connected successfully!")
                                    connectionStatus = "XMPP Connected!"
                                    scope.launch {
                                        isLoading = false
                                        showChat = true
                                    }
                                }
                                
                                override fun onXMPPClientDisconnected(client: XMPPClient) {
                                    Log.d(TAG, "❌ XMPP Client disconnected")
                                    connectionStatus = "XMPP Disconnected"
                                }
                                
                                override fun onMessageReceived(
                                    client: XMPPClient,
                                    message: com.ethora.chat.core.models.Message
                                ) {
                                    Log.d(TAG, "📨 Received message: ${message.body}")
                                }
                                
                                override fun onStanzaReceived(
                                    client: XMPPClient,
                                    stanza: com.ethora.chat.core.xmpp.XMPPStanza
                                ) {
                                    // Handle stanza
                                }
                                
                                override fun onStatusChanged(
                                    client: XMPPClient,
                                    status: ConnectionStatus
                                ) {
                                    Log.d(TAG, "🔄 Connection status changed: ${status.name}")
                                    connectionStatus = "Status: ${status.name}"
                                    
                                    // If XMPP connection fails, still show chat UI (rooms are loaded)
                                    if (status == ConnectionStatus.ERROR) {
                                        scope.launch {
                                            isLoading = false
                                            showChat = true
                                            errorMessage = "XMPP connection failed, but you can still view rooms"
                                        }
                                    }
                                }
                                
                                override fun onComposingReceived(
                                    client: XMPPClient,
                                    roomJid: String,
                                    isComposing: Boolean,
                                    composingList: List<String>
                                ) {
                                    Log.d(TAG, "📝 Composing received: room=$roomJid, composing=$isComposing, users=$composingList")
                                    // Update RoomStore with composing state (matches web: setComposing)
                                    RoomStore.setComposing(roomJid, isComposing, composingList)
                                }
                                
                                override fun onMessageEdited(
                                    client: XMPPClient,
                                    roomJid: String,
                                    messageId: String,
                                    newText: String
                                ) {
                                    Log.d(TAG, "✏️ Message edited received for $roomJid, ID: $messageId, newText: ${newText.take(50)}")
                                    com.ethora.chat.core.store.MessageStore.editMessage(roomJid, messageId, newText)
                                }
                                
                                override fun onReactionReceived(
                                    client: XMPPClient,
                                    roomJid: String,
                                    messageId: String,
                                    from: String,
                                    reactions: List<String>,
                                    data: Map<String, String>
                                ) {
                                    Log.d(TAG, "😀 Reaction received for $roomJid, messageId: $messageId, from: $from, reactions: $reactions")
                                    com.ethora.chat.core.store.MessageStore.updateReaction(roomJid, messageId, from, reactions, data)
                                }
                            })
                            
                            // Initialize XMPP client (async)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    xmppClient?.initializeClient()
                                } catch (e: Exception) {
                                    Log.e(TAG, "XMPP initialization error: ${e.message}", e)
                                    errorMessage = "XMPP connection error: ${e.message}"
                                }
                            }
                            
                            // Step 3: Load rooms (only if not already loaded from persistence)
                            val existingRooms = RoomStore.rooms.value
                            if (existingRooms.isEmpty()) {
                                connectionStatus = "Step 3: Loading rooms..."
                                Log.d(TAG, "📋 Step 3: Loading rooms from API...")
                                
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val rooms = RoomsAPIHelper.getRooms()
                                        roomsCount = rooms.size
                                        Log.d(TAG, "✅ Loaded ${rooms.size} rooms!")
                                        rooms.forEach { room ->
                                            Log.d(TAG, "   - ${room.title} (${room.jid})")
                                        }
                                        connectionStatus = "Loaded ${rooms.size} rooms!"
                                        
                                        // Save to RoomStore (will persist automatically)
                                        withContext(Dispatchers.Main) {
                                            RoomStore.setRooms(rooms)
                                            
                                            // Load persisted messages for each room
                                            rooms.forEach { room ->
                                                val messages = MessageStore.loadMessagesFromPersistence(room.jid)
                                                if (messages.isNotEmpty()) {
                                                    MessageStore.setMessagesForRoom(room.jid, messages)
                                                    Log.d(TAG, "📂 Loaded ${messages.size} persisted messages for ${room.jid}")
                                                }
                                            }
                                        }
                                        
                                        // Show chat UI after rooms are loaded
                                        scope.launch(Dispatchers.Main) {
                                            isLoading = false
                                            showChat = true
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Failed to load rooms: ${e.message}", e)
                                        errorMessage = "Failed to load rooms: ${e.message}"
                                        scope.launch(Dispatchers.Main) {
                                            isLoading = false
                                        }
                                    }
                                }
                            } else {
                                Log.d(TAG, "✅ Using ${existingRooms.size} persisted rooms")
                                roomsCount = existingRooms.size
                                connectionStatus = "Using persisted rooms"
                                isLoading = false
                                showChat = true
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error: ${e.message}", e)
                            errorMessage = e.message
                            connectionStatus = "Error: ${e.message}"
                            isLoading = false
                        }
                    }

                    if (showChat) {
                        // Show chat UI
                        val config = ChatConfig(
                            colors = ChatColors(
                                primary = "#4287f5",
                                secondary = "#42f5e9"
                            ),
                            xmppSettings = XMPPSettings(
                                devServer = "wss://xmpp.ethoradev.com:5443/ws",
                                host = "xmpp.ethoradev.com",
                                conference = "conference.xmpp.ethoradev.com"
                            ),
                            baseUrl = "https://api.ethoradev.com/v1",
                            userLogin = UserLoginConfig(
                                enabled = true,
                                user = UserStore.currentUser.value
                            )
                        )
                        
                        Chat(config = config)
                    } else {
                        // Show loading/status screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            
                            Text(
                                text = connectionStatus,
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            if (roomsCount > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Rooms loaded: $roomsCount",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            
                            errorMessage?.let { error ->
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        xmppClient?.cleanup()
    }
}
