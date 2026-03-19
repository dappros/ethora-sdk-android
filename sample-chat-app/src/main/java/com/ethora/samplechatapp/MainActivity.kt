package com.ethora.samplechatapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethora.chat.Chat
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.store.ChatStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SampleChatApp()
        }
    }
}

@Composable
private fun SampleChatApp() {
    val missingConfigFields = remember {
        collectMissingConfigFields()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (missingConfigFields.isNotEmpty()) {
                SetupRequiredScreen(missingConfigFields)
                return@Surface
            }

            val chatConfig = remember {
                ChatConfig(
                    appId = BuildConfig.ETHORA_APP_ID,
                    baseUrl = BuildConfig.ETHORA_API_BASE_URL,
                    disableRooms = true,
                    defaultLogin = false,
                    customAppToken = BuildConfig.ETHORA_APP_TOKEN,
                    chatHeaderSettings = ChatHeaderSettingsConfig(
                        roomTitleOverrides = mapOf(
                            BuildConfig.ETHORA_ROOM_JID to "Ethora Test Room"
                        )
                    ),
                    xmppSettings = XMPPSettings(
                        xmppServerUrl = BuildConfig.ETHORA_XMPP_SERVER_URL,
                        host = BuildConfig.ETHORA_XMPP_HOST,
                        conference = BuildConfig.ETHORA_XMPP_CONFERENCE
                    ),
                    jwtLogin = BuildConfig.ETHORA_USER_JWT
                        .takeIf { it.isNotBlank() }
                        ?.let { token ->
                            JWTLoginConfig(
                                enabled = true,
                                token = token
                            )
                        }
                )
            }

            LaunchedEffect(chatConfig) {
                ChatStore.setConfig(chatConfig)
                ApiClient.setBaseUrl(
                    chatConfig.baseUrl ?: AppConfig.defaultBaseURL,
                    chatConfig.customAppToken
                )
            }

            Chat(
                config = chatConfig,
                roomJID = BuildConfig.ETHORA_ROOM_JID,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun collectMissingConfigFields(): List<String> {
    val fields = linkedSetOf<String>()

    if (BuildConfig.ETHORA_APP_ID.startsWith("CHANGE_ME")) {
        fields += "ETHORA_APP_ID"
    }
    if (BuildConfig.ETHORA_API_BASE_URL.startsWith("CHANGE_ME")) {
        fields += "ETHORA_API_BASE_URL"
    }
    if (BuildConfig.ETHORA_APP_TOKEN.startsWith("CHANGE_ME")) {
        fields += "ETHORA_APP_TOKEN"
    }
    if (BuildConfig.ETHORA_ROOM_JID.startsWith("CHANGE_ME")) {
        fields += "ETHORA_ROOM_JID"
    }
    if (BuildConfig.ETHORA_XMPP_SERVER_URL.contains("CHANGE_ME")) {
        fields += "ETHORA_XMPP_SERVER_URL"
    }
    if (BuildConfig.ETHORA_XMPP_HOST.contains("CHANGE_ME")) {
        fields += "ETHORA_XMPP_HOST"
    }
    if (BuildConfig.ETHORA_XMPP_CONFERENCE.contains("CHANGE_ME")) {
        fields += "ETHORA_XMPP_CONFERENCE"
    }

    return fields.toList()
}

@Composable
private fun SetupRequiredScreen(missingFields: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Sample app requires Ethora credentials",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Update buildConfigField values in sample-chat-app/build.gradle.kts:"
        )
        missingFields.forEach { field ->
            Text(text = "- $field", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "ETHORA_USER_JWT is optional. Leave it empty if you log in another way.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
