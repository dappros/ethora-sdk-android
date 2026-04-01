# Ethora Chat Component (Android)

This SDK gives you a ready-to-use chat UI + chat core for Android (Jetpack Compose).

## Install Option 1: JitPack

JitPack build page (already available): `https://jitpack.io/#dappros/ethora-sdk-android/v1.0.0`

### 1. Add JitPack repository

Project-level `settings.gradle.kts`:

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

App module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.dappros.ethora-sdk-android:ethora-component:1.0.0")
}
```

If you need to pin to a specific commit instead of a tag, replace `1.0.0` with a commit hash.

## Install Option 2: Manual Source Copy

Use this if you do not want JitPack and want SDK sources inside your app repo.

### 1. Copy folders into your Android project

Copy these folders from this repo into your project root:

- `ethora-component`
- `chat-core`
- `chat-ui`

Keep this structure so `ethora-component` can find shared sources:

- `<your-project>/ethora-component`
- `<your-project>/chat-core`
- `<your-project>/chat-ui`

### 2. Include module

In your app project `settings.gradle.kts`:

```kotlin
include(":ethora-component")
```

### 3. Add local module dependency

In app module `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":ethora-component"))
}
```

### 4. Ensure permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Push Setup (Universal Config)

Push settings must come from config/env, not hardcoded values.

For `sample-chat-app`, create local file `sample-chat-app/.env` (do not commit it) and provide:

```env
ETHORA_APPLICATION_ID=com.yourcompany.yourapp
ETHORA_APP_ID=YOUR_ETHORA_APP_ID
ETHORA_API_BASE_URL=https://api.example.com/v1/
ETHORA_USER_JWT=YOUR_JWT_OR_EMPTY
ETHORA_ROOM_JID=OPTIONAL_ROOM_JID
ETHORA_XMPP_SERVER_URL=wss://xmpp.example.com/ws
ETHORA_XMPP_HOST=xmpp.example.com
ETHORA_XMPP_CONFERENCE=conference.xmpp.example.com
ETHORA_DNS_FALLBACK_OVERRIDES=
```

`applicationId` is now resolved from `ETHORA_APPLICATION_ID` / `APPLICATION_ID`.

### google-services.json (manual)

`google-services.json` is intentionally not stored in this repository.

Add it manually to:

- `sample-chat-app/google-services.json`

Important:

- In Firebase, Android app package name must match `ETHORA_APPLICATION_ID`.
- If package names differ, FCM token and push delivery will fail.

## Chat Configuration

The most reliable integration flow is:

1. Create `ChatConfig` in `remember(...)`.
2. Apply config with `ChatStore.setConfig(...)`.
3. Set API URL/token with `ApiClient.setBaseUrl(...)`.
4. Render `Chat(...)`.

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

### Config Notes

- `disableRooms = true` + `roomJID` in `Chat(...)` gives you single-room mode.
- `jwtLogin` is used when `enabled = true` and token is provided.
- `chatHeaderSettings.roomTitleOverrides` lets you replace raw JID in header.
- `dnsFallbackOverrides` helps when emulator DNS cannot resolve your hosts.
- `customAppToken` is forwarded via `ApiClient.setBaseUrl(...)`.

## Unread Counter Hook

SDK exports a composable hook:

- `useUnread(maxCount: Int = 10): UnreadState`
- `UnreadState.totalCount` (Int)
- `UnreadState.displayCount` (String, for example `10+`)

### Example: show unread badge in host app tab bar

```kotlin
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ethora.chat.useUnread

@Composable
fun ChatTabBadge() {
    val unread = useUnread(maxCount = 99)

    BadgedBox(
        badge = {
            if (unread.totalCount > 0) {
                Badge { Text(unread.displayCount) }
            }
        }
    ) {
        Text("Chat")
    }
}
```

### Important behavior

- The unread state comes from SDK `RoomStore`.
- Unread values are meaningful after chat data is loaded (the `Chat(...)` flow has initialized rooms/session).
- If chat is not initialized yet, unread will be `0`.

## Build / Validate

```bash
./gradlew :ethora-component:assemble
```

## JitPack Build File

This repo includes `jitpack.yml` and is configured for:

- `groupId`: `com.github.dappros.ethora-sdk-android`
- `artifactId`: `ethora-component`

Client dependency format:

```kotlin
implementation("com.github.dappros.ethora-sdk-android:ethora-component:1.0.0")
```
