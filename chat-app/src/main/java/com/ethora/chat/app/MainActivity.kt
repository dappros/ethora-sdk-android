package com.ethora.chat.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
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
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.store.MessageLoader
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

    enum class AppScreen {
        INITIALIZING,
        LOGIN,
        CHAT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.INITIALIZING) }
                    var isLoading by remember { mutableStateOf(false) }
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    
                    // Persistent states
                    val scope = rememberCoroutineScope()
                    var email by remember { mutableStateOf("yukiraze9@gmail.com") }
                    var password by remember { mutableStateOf("Qwerty123") }

                    // Initial Persistence Setup
                    LaunchedEffect(Unit) {
                        try {
                            // Initialize persistence managers (once per app lifetime)
                            val persistenceManager = ChatPersistenceManager(this@MainActivity)
                            val chatDatabase = ChatDatabase.getDatabase(this@MainActivity)
                            val messageCache = MessageCache(chatDatabase)
                            
                            RoomStore.initialize(persistenceManager)
                            UserStore.initialize(persistenceManager)
                            MessageStore.initialize(messageCache)
                            ScrollPositionStore.initialize(this@MainActivity)
                            
                            val localStorage = LocalStorage(this@MainActivity)
                            MessageLoader.initialize(localStorage)
                            
                            // Load persisted user if any (but still show login if user wants to test login)
                            val persistedUser = withContext(Dispatchers.IO) {
                                UserStore.loadUserFromPersistence()
                            }
                            
                            if (persistedUser != null) {
                                UserStore.setUser(persistedUser)
                                ApiClient.setUserToken(persistedUser.token ?: "")
                            }
                            
                            currentScreen = AppScreen.LOGIN
                        } catch (e: Exception) {
                            Log.e(TAG, "Initialization failed", e)
                            errorMessage = "Initialization failed: ${e.message}"
                        }
                    }

                    // Setup logout callback to return to login screen
                    LaunchedEffect(Unit) {
                        LogoutService.setOnLogoutCallback {
                            currentScreen = AppScreen.LOGIN
                            isLoading = false
                        }
                    }

                    when (currentScreen) {
                        AppScreen.INITIALIZING -> {
                            LoadingScreen("Initializing persistence...")
                        }
                        AppScreen.LOGIN -> {
                            LoginScreen(
                                email = email,
                                password = password,
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                onEmailChange = { email = it },
                                onPasswordChange = { password = it },
                                onLogin = {
                                    isLoading = true
                                    errorMessage = null
                                    scope.launch {
                                        try {
                                            val response = withContext(Dispatchers.IO) {
                                                AuthAPIHelper.loginWithEmail(email, password, baseUrl = "https://api.ethoradev.com/v1")
                                            }
                                            UserStore.setUser(response)
                                            ApiClient.setUserToken(response.token)
                                            currentScreen = AppScreen.CHAT
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Login failed", e)
                                            errorMessage = "Login failed: ${e.message}"
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            )
                        }
                        AppScreen.CHAT -> {
                            ChatScreen(
                                onLogout = {
                                    LogoutService.performLogout()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    email: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Ethora Chat Test App", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Login")
            }
        }
        
        errorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ethora Chat") },
                actions = {
                    Button(onClick = onLogout) {
                        Text("Logout")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Chat") },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                // Main Chat component from chat-ui
                val config = ChatConfig(
                    disableHeader = false,
                    disableRooms = false,
                    disableMedia = false,
                    newArch = true,
                    disableProfilesInteractions = true,
                    baseUrl = "https://api.ethoradev.com/v1",
                    xmppSettings = XMPPSettings(
                        devServer = "wss://xmpp.ethoradev.com:5443/ws",
                        host = "xmpp.ethoradev.com",
                        conference = "conference.xmpp.ethoradev.com"
                    ),
                    enableRoomsRetry = com.ethora.chat.core.config.EnableRoomsRetryConfig(
                        enabled = true,
                        helperText = "Initializing room"
                    )
                )
                
                Chat(config = config)
            } else {
                com.ethora.chat.ui.components.LogsView()
            }
        }
    }
}

