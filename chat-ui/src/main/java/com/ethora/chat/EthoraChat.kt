package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.models.User
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.service.LogoutService
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.ui.components.ChatRoomView
import com.ethora.chat.ui.components.RoomListView
import com.ethora.chat.ui.styling.ChatTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    ChatStore.setConfig(config)
    
    // Set base URL and app token if provided
    LaunchedEffect(config.baseUrl, config.customAppToken) {
        config.baseUrl?.let { baseUrl ->
            ApiClient.setBaseUrl(baseUrl, config.customAppToken)
        }
    }
    
    // Initialize user - priority: direct user param > config.userLogin.user > JWT login > default login
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
            // JWT login
            jwtLogin?.enabled == true -> {
                val token = jwtLogin.token
                if (token != null) {
                    val baseUrl = config.baseUrl ?: com.ethora.chat.core.config.AppConfig.defaultBaseURL
                    val loginResponse = AuthAPIHelper.loginViaJWT(token, baseUrl)
                    loginResponse?.let { UserStore.setUser(it) }
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
        if (existingRooms.isEmpty() && currentUser != null) {
            // No rooms loaded yet, load them
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val rooms = RoomsAPIHelper.getRooms()
                    RoomStore.setRooms(rooms)
                    android.util.Log.d("EthoraChat", "✅ Loaded ${rooms.size} rooms globally")
                } catch (e: Exception) {
                    android.util.Log.e("EthoraChat", "❌ Failed to load rooms", e)
                }
            }
        } else if (existingRooms.isNotEmpty()) {
            android.util.Log.d("EthoraChat", "⏭️ Rooms already loaded (${existingRooms.size} rooms), skipping API request")
        }
    }
    
    ChatTheme(colors = config.colors) {
        // Initialize XMPP client if user is available
        val xmppClient = remember(currentUser) {
            currentUser?.let { user ->
                user.xmppUsername?.let { username ->
                    user.xmppPassword?.let { password ->
                        val client = XMPPClient(
                            username = username,
                            password = password,
                            settings = config.xmppSettings
                        )
                        
                        // Set XMPP client in LogoutService so external apps can logout
                        LogoutService.setXMPPClient(client)
                        
                        CoroutineScope(Dispatchers.IO).launch {
                            client.initializeClient()
                        }
                        
                        client
                    }
                }
            }
        }
        
        // Load initial messages for all rooms once after XMPP client is initialized and rooms are loaded
        // Similar to updateMessagesTillLast in web version
        LaunchedEffect(xmppClient, RoomStore.rooms.value) {
            val rooms = RoomStore.rooms.value
            val client = xmppClient
            
            // Wait for XMPP client to be fully connected and rooms to be loaded
            if (client != null && rooms.isNotEmpty()) {
                try {
                    // Small delay to ensure XMPP is fully connected
                    delay(1000)
                    
                    // Load initial messages for all rooms (only once)
                    MessageLoader.loadInitialMessagesForAllRooms(
                        xmppClient = client,
                        batchSize = 5,
                        messagesPerRoom = 30
                    )
                } catch (e: CancellationException) {
                    // Expected when composable leaves composition - don't log as error
                    // Re-throw to allow proper coroutine cancellation
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("EthoraChat", "Error loading initial messages", e)
                }
            }
        }
        
        // Show room list or single room based on config
        if (config.disableRooms == true) {
            // Single room mode - use roomJID if provided, otherwise use first default room
            val targetRoom = roomJID?.let { jid ->
                // Find room by JID from RoomStore
                com.ethora.chat.core.store.RoomStore.getRoomByJid(jid)
            } ?: config.defaultRooms?.firstOrNull()
            
            targetRoom?.let { room ->
                ChatRoomView(
                    room = room,
                    xmppClient = xmppClient,
                    onBack = { /* Handle back */ },
                    modifier = modifier
                )
            }
        } else {
            // Room list mode
            var selectedRoom by remember { mutableStateOf<com.ethora.chat.core.models.Room?>(null) }
            
            if (selectedRoom != null) {
                ChatRoomView(
                    room = selectedRoom!!,
                    xmppClient = xmppClient,
                    onBack = { selectedRoom = null },
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
