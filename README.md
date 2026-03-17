# Ethora Chat Android SDK

Single-package chat SDK for Android Kotlin apps.

## Can I install it in any Kotlin app?

Yes, for any **Android Kotlin app** (Jetpack Compose UI) you can integrate it as one package:

- Maven artifact: `com.ethora:ethora-component:<version>`
- Or local module: `:ethora-component`

You no longer need to include `chat-core`, `chat-ui`, or `chat-app` in a client app.

## Current project modules

This repository currently includes:

- `:ethora-component` (SDK library)
- `:ethora-test-app` (sample app for local testing)

## Quick start (library consumer)

### 1. Add dependency

In your app module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.ethora:ethora-component:1.0.0")
}
```

`1.0.0` is the current version in this repo (`gradle/libs.versions.toml`).

If you use a private Maven repo, add it in your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("YOUR_MAVEN_REPO_URL") }
    }
}
```

### 2. Add required Android permissions

In your app `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 3. Render chat

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
fun EthoraChatScreen(userToken: String) {
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
        // newArch defaults to true
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

## Config notes

- `newArch` defaults to `true` (you can omit it).
- `XMPPSettings` uses `xmppServerUrl` (backward-compatible `devServer` alias still exists internally).
- For JWT login use:
  - `jwtLogin = JWTLoginConfig(token = "...", enabled = true)`
- To force single room:
  - `disableRooms = true`
  - pass `roomJID` to `Chat(...)`
- Header customization:
  - `roomTitleOverrides` to override title by room JID
  - `chatInfoButtonDisabled = true` to hide 3-dot button
  - `backButtonDisabled = true` to hide back button

## Local development in this repository

### Run sample app on emulator/device

```bash
./gradlew :ethora-test-app:installDebug
adb shell am start -n com.ethora.test.app/.MainActivity
```

### List available modules

```bash
./gradlew projects
```

## Publish SDK artifact (for SDK maintainers)

The `ethora-component` module is configured with:

- `groupId = "com.ethora"`
- `artifactId = "ethora-component"`
- `version = versionName` from `gradle/libs.versions.toml`

### Publish to local Maven cache

```bash
./gradlew :ethora-component:publishToMavenLocal
```

Then consume with:

```kotlin
repositories {
    mavenLocal()
    google()
    mavenCentral()
}

dependencies {
    implementation("com.ethora:ethora-component:1.0.0")
}
```

### Publish to remote Maven repository

Add repository + credentials under `publishing { repositories { ... } }` in `ethora-component/build.gradle.kts`, then:

```bash
./gradlew :ethora-component:publish
```

## Troubleshooting

- `project not found ':ethora-test-app'`:
  - check `settings.gradle.kts` includes `include(":ethora-test-app")` when running sample app tasks.
- JWT not used:
  - ensure token is non-empty and `enabled = true`.
- Opens room list instead of room:
  - set `disableRooms = true` and pass `roomJID` to `Chat(...)`.
- Header still shows default room JID:
  - add `chatHeaderSettings.roomTitleOverrides` for that room JID.
