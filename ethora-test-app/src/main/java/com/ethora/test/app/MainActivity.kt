package com.ethora.test.app

import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.ui.styling.ChatTheme

class MainActivity : ComponentActivity() {
    private val singleRoomJid = "699c6923429c2757ac8ab6a4_playground-room-1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasUserToken = BuildConfig.USER_TOKEN.isNotBlank()
        Log.d(
            "EthoraTestApp",
            "USER_TOKEN from BuildConfig present=$hasUserToken length=${BuildConfig.USER_TOKEN.length}"
        )

        val appConfig = ChatConfig(
            appId = BuildConfig.APP_ID,
            baseUrl = BuildConfig.API_BASE_URL,
            disableRooms = true,
            chatHeaderSettings = ChatHeaderSettingsConfig(
                roomTitleOverrides = mapOf(
                    singleRoomJid to "Playground Room 1"
                ),
                chatInfoButtonDisabled = true,
                backButtonDisabled = true
            ),
            xmppSettings = XMPPSettings(
                xmppServerUrl = BuildConfig.XMPP_DEV_SERVER,
                host = BuildConfig.XMPP_HOST,
                conference = BuildConfig.XMPP_CONFERENCE
            ),
            jwtLogin = JWTLoginConfig(
                token = BuildConfig.USER_TOKEN,
                enabled = true
            ),
            defaultLogin = false
        )

        ChatStore.setConfig(appConfig)
        ApiClient.setBaseUrl(appConfig.baseUrl ?: AppConfig.defaultBaseURL, appConfig.customAppToken)

        setContent {
            ChatTheme {
                var selectedTab by remember { mutableStateOf(1) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                label = { Text("Home") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                                label = { Text("Chat") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.List, contentDescription = "Logs") },
                                label = { Text("Logs") }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        when (selectedTab) {
                            0 -> HomeTab()
                            1 -> ChatTab(config = appConfig, roomJid = singleRoomJid)
                            2 -> LogsTab()
                        }
                    }
                }
            }
        }
    }
}
