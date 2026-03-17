# Ethora Chat Component (Android)

Install and run chat in your Android app using one dependency.

## Client Quick Start (JitPack)

### 1. Add JitPack repository

In project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

### 2. Add dependency

In app module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.dappros.ethora-sdk-android:ethora-component:<TAG>")
}
```

Use one of these versions:

- Recommended: a release tag, for example `v1.0.0`
- Fallback: a commit hash, for example `9763836`

Commit-hash example:

```kotlin
dependencies {
    implementation("com.github.dappros.ethora-sdk-android:ethora-component:9763836")
}
```

## Required Android permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Compose Usage (Single Room)

The idea is simple:

1. Build `ChatConfig` once with `remember(...)`.
2. Push config into `ChatStore`.
3. Set API base URL and app token in `ApiClient`.
4. Render `Chat(...)` with a specific `roomJID`.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.fillMaxSize
import com.ethora.chat.Chat
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.store.ChatStore

@Composable
fun EthoraChatScreen(
    singleRoomJid: String,
    dnsOverrides: Map<String, String>?,
    loggedInUser: com.ethora.chat.core.models.User?,
    hasJwtToken: Boolean
) {
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
            // userLogin = loggedInUser?.let { user ->
            //    UserLoginConfig(enabled = true, user = user)
            // },
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

    Chat(
        config = appConfig,
        roomJID = singleRoomJid,
        modifier = Modifier.fillMaxSize()
    )
}
```

## How this config behaves

- `disableRooms = true` puts the UI in single-room mode.
- `roomTitleOverrides` lets you show a clean header title instead of raw room JID.
- `chatInfoButtonDisabled` and `backButtonDisabled` simplify header actions.
- `jwtLogin` is applied only when you actually have a JWT token.
- `customAppToken` is passed to backend requests through `ApiClient.setBaseUrl(...)`.
- `dnsFallbackOverrides` is useful for emulator or restricted DNS environments.

## Local Module Usage (Inside This Repo)

If your app lives inside this repo, you can use the module directly:

```kotlin
dependencies {
    implementation(project(":ethora-component"))
}
```

## Build Library

```bash
./gradlew :ethora-component:assemble
```

## Publish to Maven Local

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
