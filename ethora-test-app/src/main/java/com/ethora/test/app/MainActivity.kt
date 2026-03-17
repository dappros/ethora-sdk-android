package com.ethora.test.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.UserLoginConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.models.User
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.ui.styling.ChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppScreen {
    LOGIN,
    CHAT
}

class MainActivity : ComponentActivity() {
    private val singleRoomJid = "699c6923429c2757ac8ab6a4_playground-room-1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize persistence and stores (required for message cache + room state)
        initChatStores(this)

        setContent {
            ChatTheme {
                val hasJwtToken = BuildConfig.USER_TOKEN.isNotBlank()
                var currentScreen by remember {
                    mutableStateOf(if (hasJwtToken) AppScreen.CHAT else AppScreen.LOGIN)
                }
                var selectedTab by remember { mutableStateOf(1) }
                var email by remember { mutableStateOf(BuildConfig.DEFAULT_LOGIN_EMAIL) }
                var password by remember { mutableStateOf(BuildConfig.DEFAULT_LOGIN_PASSWORD) }
                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var loggedInUser by remember { mutableStateOf<User?>(null) }
                val scope = rememberCoroutineScope()

                val dnsOverrides = remember {
                    BuildConfig.DNS_FALLBACK_OVERRIDES
                        .split(",")
                        .mapNotNull { part ->
                            val kv = part.split("=", limit = 2).map { it.trim() }
                            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
                        }
                        .toMap()
                        .takeIf { it.isNotEmpty() }
                }
                val appConfig = remember(loggedInUser, dnsOverrides) {
                    ChatConfig(
                        appId = BuildConfig.APP_ID,
                        baseUrl = BuildConfig.API_BASE_URL,
                        disableRooms = true,
                        chatHeaderSettings = ChatHeaderSettingsConfig(
                            roomTitleOverrides = mapOf(singleRoomJid to "Playground Room 1"),
                            chatInfoButtonDisabled = true,
                            backButtonDisabled = true
                        ),
                        xmppSettings = XMPPSettings(
                            xmppServerUrl = BuildConfig.XMPP_DEV_SERVER,
                            host = BuildConfig.XMPP_HOST,
                            conference = BuildConfig.XMPP_CONFERENCE
                        ),
                        dnsFallbackOverrides = dnsOverrides,
                        userLogin = loggedInUser?.let { user ->
                            UserLoginConfig(enabled = true, user = user)
                        },
                        jwtLogin = if (hasJwtToken) {
                            JWTLoginConfig(
                                token = BuildConfig.USER_TOKEN,
                                enabled = true
                            )
                        } else null,
                        defaultLogin = false,
                        customAppToken = BuildConfig.API_TOKEN
                    )
                }

                ChatStore.setConfig(appConfig)
                ApiClient.setBaseUrl(appConfig.baseUrl ?: AppConfig.defaultBaseURL, appConfig.customAppToken)

                when (currentScreen) {
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
                                            AuthAPIHelper.loginWithEmail(email.trim(), password)
                                        }
                                        UserStore.setUser(response)
                                        ApiClient.setUserToken(response.token)
                                        loggedInUser = response.user.toUser().copy(
                                            token = response.token,
                                            refreshToken = response.refreshToken
                                        )
                                        currentScreen = AppScreen.CHAT
                                        Log.d("EthoraTestApp", "Logged in via email/password")
                                    } catch (e: Exception) {
                                        errorMessage = "Login failed: ${e.message}"
                                        Log.e("EthoraTestApp", "Email/password login failed", e)
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        )
                    }

                    AppScreen.CHAT -> {
                        Scaffold(
                            bottomBar = {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        icon = { androidx.compose.material3.Icon(Icons.Default.Home, contentDescription = "Home") },
                                        label = { Text("Home") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        icon = { androidx.compose.material3.Icon(Icons.Default.Chat, contentDescription = "Chat") },
                                        label = { Text("Chat") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = { selectedTab = 2 },
                                        icon = { androidx.compose.material3.Icon(Icons.Default.List, contentDescription = "Logs") },
                                        label = { Text("Logs") }
                                    )
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                // Keep ChatTab always composed when on CHAT screen so JWT login
                                // is not cancelled when switching to Home/Logs tabs
                                Box(modifier = Modifier.fillMaxSize()) {
                                    ChatTab(config = appConfig, roomJid = singleRoomJid)
                                }
                                // Overlay other tabs when selected (Chat stays mounted underneath)
                                if (selectedTab != 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        when (selectedTab) {
                                            0 -> HomeTab()
                                            2 -> LogsTab()
                                            else -> { }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initChatStores(context: android.content.Context) {
        val appContext = context.applicationContext
        val persistenceManager = ChatPersistenceManager(appContext)
        val chatDatabase = ChatDatabase.getDatabase(appContext)
        val messageCache = MessageCache(chatDatabase)

        RoomStore.initialize(persistenceManager)
        UserStore.initialize(persistenceManager)
        MessageStore.initialize(messageCache)
        ScrollPositionStore.initialize(appContext)
        MessageLoader.initialize(LocalStorage(appContext))
    }
}

@Composable
private fun LoginScreen(
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
        Text(text = "Ethora Test App", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onLogin,
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Logging in..." else "Login")
        }
        errorMessage?.let { err ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = err, color = MaterialTheme.colorScheme.error)
        }
    }
}
