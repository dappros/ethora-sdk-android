Here is an instruction on how to install Ethora Android Chat Component.
I have attached the screenshot with steps to install.

1. Install the package from https://github.com/dappros/ethora-sdk-android
1. Then follow steps from screenshot:
Select your project (step 1 on screenshot)
Open `settings.gradle.kts` and include the chat modules (step 2 on screenshot)
On opened file add project path to `include` and `project(":chat-ui").projectDir` 
After Android Studio will ask if you want to sync - click Sync Now
Then you should see that package was successfully added (step 3 on screenshot)
2. Now you can add the Chat Component to your files.
This is code snippet that should work for you:

```kotlin
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import com.ethora.chat.Chat
import com.ethora.chat.core.config.*
import com.ethora.chat.core.service.LogoutService

fun getChatConfig(
    chatToken: String?
): ChatConfig {
    return ChatConfig(
        disableHeader = true,
        disableRooms = true,
        jwtLogin = JWTLoginConfig(token = chatToken ?: "", enabled = true),
        enableRoomsRetry = EnableRoomsRetryConfig(enabled = true, helperText = "Initializing room"),
        newArch = true,
        baseUrl = "https://api.ethoradev.com/v1",
        xmppSettings = XMPPSettings(
            devServer = "wss://xmpp.ethoradev.com:5443/ws",
            host = "xmpp.ethoradev.com",
            conference = "conference.xmpp.ethoradev.com"
        ),
        disableProfilesInteractions = true
    )
}

@Composable
fun MainChatView(chatToken: String?, onLogout: () -> Unit) {
    val config = remember(chatToken) { getChatConfig(chatToken) }
    
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Chat") },
                actions = {
                    Button(onClick = {
                        LogoutService.performLogout()
                        onLogout()
                    }) {
                        Text("Logout")
                    }
                }
            )
        }
    ) { padding ->
        Chat(
            config = config,
            modifier = Modifier.padding(padding)
        )
    }
}
```

Also in the test file you can see how logoutService is used for switching users.

Other config is as close as possible to the web version.
Please note that this is beta. If you find any bugs - feel free to tell me. From our side we will provide updates as soon as possible and keep you informed about all updates.
