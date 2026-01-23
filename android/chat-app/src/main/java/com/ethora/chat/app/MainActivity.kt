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
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.core.xmpp.XMPPClientDelegate
import com.ethora.chat.core.xmpp.ConnectionStatus
import com.ethora.chat.ui.styling.ChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                            
                            // Step 1: Login
                            connectionStatus = "Step 1: Logging in..."
                            Log.d(TAG, "🔐 Step 1: Logging in with email...")
                            
                            val loginResponse = AuthAPIHelper.loginWithEmail(email, password)
                            
                            // Save to UserStore
                            UserStore.setUser(loginResponse)
                            
                            // Set token in API client
                            ApiClient.setUserToken(loginResponse.token)
                            
                            // Save to UserStore
                            UserStore.setUser(loginResponse)
                            
                            Log.d(TAG, "✅ Login successful! User stored in UserStore")
                            connectionStatus = "Login successful!"
                            
                            // Step 2: Connect XMPP
                            connectionStatus = "Step 2: Connecting to XMPP server..."
                            Log.d(TAG, "🔐 Step 2: Connecting to XMPP server...")
                            
                            val currentUser = UserStore.currentUser.value
                            if (currentUser == null) {
                                throw Exception("User not found in store")
                            }
                            val xmppUsername = currentUser.xmppUsername ?: email
                            val xmppPassword = currentUser.xmppPassword ?: password
                            
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
                            
                            // Step 3: Test loading rooms
                            connectionStatus = "Step 3: Loading rooms..."
                            Log.d(TAG, "📋 Step 3: Testing room loading...")
                            
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val rooms = RoomsAPIHelper.getRooms()
                                    roomsCount = rooms.size
                                    Log.d(TAG, "✅ Loaded ${rooms.size} rooms!")
                                    rooms.forEach { room ->
                                        Log.d(TAG, "   - ${room.title} (${room.jid})")
                                    }
                                    connectionStatus = "Loaded ${rooms.size} rooms!"
                                    
                                    // Show chat UI after rooms are loaded (don't wait for XMPP)
                                    // XMPP connection can happen in background
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
