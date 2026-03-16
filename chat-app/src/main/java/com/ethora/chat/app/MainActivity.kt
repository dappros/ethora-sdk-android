package com.ethora.chat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.EnableRoomsRetryConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.config.ChatColors
import com.ethora.chat.core.config.UserLoginConfig
import com.ethora.chat.core.models.User
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.push.PushNotificationManager
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.ChatStore
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
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Notification permission granted: $granted")
            if (granted) fetchFcmToken()
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val jid = intent?.getStringExtra(EthoraPushService.EXTRA_ROOM_JID)
        if (jid != null) {
            Log.d(TAG, "Notification intent with room JID: $jid")
            PushNotificationManager.setPendingNotificationJid(jid)
        }
    }

    private fun requestNotificationPermission() {
        Log.d(TAG, "🔔 Requesting notification permission (SDK=${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "🔔 Permission not granted, launching request")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "🔔 Permission already granted")
                fetchFcmToken()
            }
        } else {
            Log.d(TAG, "🔔 SDK < 33, no runtime permission needed")
            fetchFcmToken()
        }
    }

    private fun fetchFcmToken(attempt: Int = 1) {
        val maxAttempts = 5
        Log.d(TAG, "🔔 Fetching FCM token (attempt $attempt/$maxAttempts)...")

        if (attempt == 1) {
            // On first attempt, delete old token and reset Firebase Installation to get clean state
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { deleteTask ->
                Log.d(TAG, "🔔 Old token deleted: ${deleteTask.isSuccessful}")
                requestNewFcmToken(attempt)
            }
        } else if (attempt == 3) {
            // On 3rd attempt, reset Firebase Installations entirely
            Log.d(TAG, "🔔 Resetting Firebase Installations...")
            FirebaseInstallations.getInstance().delete().addOnCompleteListener { delTask ->
                Log.d(TAG, "🔔 Firebase Installation deleted: ${delTask.isSuccessful}")
                requestNewFcmToken(attempt)
            }
        } else {
            requestNewFcmToken(attempt)
        }
    }

    private fun requestNewFcmToken(attempt: Int) {
        val maxAttempts = 5
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d(TAG, "🔔 FCM token obtained: ${token.take(20)}...")
                PushNotificationManager.setFcmToken(token)
            } else {
                Log.e(TAG, "🔔 Failed to get FCM token (attempt $attempt)", task.exception)
                if (attempt < maxAttempts) {
                    val delayMs = (attempt * 10000).toLong()
                    Log.d(TAG, "🔔 Will retry FCM token in ${delayMs}ms...")
                    CoroutineScope(Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(delayMs)
                        fetchFcmToken(attempt + 1)
                    }
                } else {
                    Log.e(TAG, "🔔 Exhausted all $maxAttempts attempts to get FCM token")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PushNotificationManager.initialize(this)
        handleNotificationIntent(intent)

        // Log Firebase configuration for debugging
        try {
            val app = FirebaseApp.getInstance()
            Log.d(TAG, "🔔 Firebase app: ${app.name}")
            Log.d(TAG, "🔔 Firebase appId: ${app.options.applicationId}")
            Log.d(TAG, "🔔 Firebase projectId: ${app.options.projectId}")
            Log.d(TAG, "🔔 Firebase gcmSenderId: ${app.options.gcmSenderId}")
            Log.d(TAG, "🔔 Firebase apiKey: ${app.options.apiKey?.take(15)}...")
        } catch (e: Exception) {
            Log.e(TAG, "🔔 Firebase not initialized!", e)
        }

        requestNotificationPermission()

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
                    var email by remember { mutableStateOf(BuildConfig.DEFAULT_LOGIN_EMAIL) }
                    var password by remember { mutableStateOf(BuildConfig.DEFAULT_LOGIN_PASSWORD) }

                    // Config: loaded from .env at build time (like React's env), set early for all API calls
                    val appConfig = remember {
                        ChatConfig(
                            baseUrl = BuildConfig.API_BASE_URL,
                            appId = BuildConfig.APP_ID,
                            xmppSettings = XMPPSettings(
                                devServer = BuildConfig.XMPP_DEV_SERVER,
                                host = BuildConfig.XMPP_HOST,
                                conference = BuildConfig.XMPP_CONFERENCE
                            ),
                            disableHeader = false,
                            disableMedia = false,
                            newArch = true,
                            disableProfilesInteractions = true,
                            enableRoomsRetry = EnableRoomsRetryConfig(enabled = true, helperText = "Initializing room")
                        )
                    }
                    ChatStore.setConfig(appConfig)
                    ApiClient.setBaseUrl(appConfig.baseUrl ?: AppConfig.defaultBaseURL, appConfig.customAppToken)

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
                                                AuthAPIHelper.loginWithEmail(email, password)
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
                                config = appConfig,
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
fun ChatScreen(config: ChatConfig, onLogout: () -> Unit) {
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
                Chat(config = config)
            } else {
                com.ethora.chat.ui.components.LogsView()
            }
        }
    }
}

