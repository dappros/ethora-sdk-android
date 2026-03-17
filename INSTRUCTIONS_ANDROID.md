# Ethora Chat Android Integration Instructions

This SDK is integrated as **one package**: `ethora-component`.

## 1. Add dependency

In your app module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.ethora:ethora-component:1.0.0")
}
```

If your team uses a private Maven repo, add it to root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("YOUR_MAVEN_REPO_URL") }
    }
}
```

## 2. Add manifest permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## 3. Create chat config and render chat

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.fillMaxSize
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.store.ChatStore

@Composable
fun ChatScreen(userToken: String) {
    val roomJid = "699c6923429c2757ac8ab6a4_playground-room-1"

    val config = ChatConfig(
        appId = "YOUR_APP_ID",
        baseUrl = "https://api.messenger-dev.vitall.com",
        disableRooms = true,
        chatHeaderSettings = ChatHeaderSettingsConfig(
            roomTitleOverrides = mapOf(roomJid to "Playground Room 1"),
            chatInfoButtonDisabled = true,
            backButtonDisabled = true
        ),
        xmppSettings = XMPPSettings(
            xmppServerUrl = "wss://xmpp.messenger-dev.vitall.com/ws",
            host = "xmpp.messenger-dev.vitall.com",
            conference = "conference.xmpp.messenger-dev.vitall.com"
        ),
        jwtLogin = JWTLoginConfig(
            token = userToken,
            enabled = true
        ),
        defaultLogin = false
        // newArch is true by default
    )

    ChatStore.setConfig(config)
    ApiClient.setBaseUrl(config.baseUrl ?: "https://api.messenger-dev.vitall.com", config.customAppToken)

    Chat(
        config = config,
        roomJID = roomJid,
        modifier = Modifier.fillMaxSize()
    )
}
```

## 4. Important config behavior

- `newArch` is enabled by default.
- Use `XMPPSettings.xmppServerUrl` as the main server URL field.
- `jwtLogin.enabled = true` + non-empty token enables JWT login flow.
- `disableRooms = true` + `roomJID` opens a specific room directly.
- Header controls:
  - `roomTitleOverrides` custom room title
  - `chatInfoButtonDisabled` hides 3-dot menu
  - `backButtonDisabled` hides back button

## 5. If integrating from source (same repo checkout)

In `settings.gradle.kts`:

```kotlin
include(":ethora-component")
```

In your app module:

```kotlin
dependencies {
    implementation(project(":ethora-component"))
}
```
