# Ethora SDK for Android (`ethora-component`)

Production-ready Android chat SDK built with Kotlin + Jetpack Compose.

This package ships a complete chat experience (room list + room view + media + reactions + typing + push hooks) and exposes host-facing APIs for configuration, connection state, unread counters, event interception, and UI extension.

## Table of Contents

- [What You Get](#what-you-get)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Installation](#installation)
- [Host App Setup](#host-app-setup)
- [Quick Start](#quick-start)
- [Authentication Modes](#authentication-modes)
- [Single Room vs Multi Room](#single-room-vs-multi-room)
- [Public APIs for Host UI](#public-apis-for-host-ui)
- [ChatConfig Reference](#chatconfig-reference)
- [Event and Send Interception](#event-and-send-interception)
- [Custom UI Components](#custom-ui-components)
- [Push Notifications (FCM)](#push-notifications-fcm)
- [Persistence and Offline Behavior](#persistence-and-offline-behavior)
- [Logout](#logout)
- [Troubleshooting](#troubleshooting)
- [Production Checklist](#production-checklist)

## What You Get

- Compose chat UI with room list and room screen.
- Real-time messaging over XMPP WebSocket.
- History loading + incremental sync after reconnect.
- Unread counters and host-facing connection status hook.
- Media messages (image/video/audio/files), persistent unsent-media retry queue, full-screen image viewer, and native cached PDF preview.
- Message actions: edit, delete, reply, reactions.
- Typing indicators.
- URL auto-linking + URL preview cards.
- Push integration hooks (FCM token/backend subscription + room MUC-SUB flow).
- Local persistence for user/session metadata, rooms, message cache, and scroll position.
- Extensibility hooks: event stream, send interception, custom composables.

## Architecture

This repository contains:

- `ethora-component`: distributable SDK artifact (published to JitPack).
- `chat-core`: networking, XMPP, stores, models, persistence, push manager.
- `chat-ui`: Compose UI + hooks (`Chat`, `useUnread`, `useConnectionState`, `reconnectChat`).
- `sample-chat-app`: reference app with full integration.

Important: the published artifact is `ethora-component`, but it packages code from `chat-core` and `chat-ui` via source sets.

## Requirements

- Android `minSdk 26`
- `compileSdk 34`, `targetSdk 34`
- Java/Kotlin target `17`
- Kotlin `2.3.0`, AGP `8.7.0`, Gradle `9.4.1`
- Host must apply `id("org.jetbrains.kotlin.plugin.compose")` (required since Kotlin 2.0 — `composeOptions.kotlinCompilerExtensionVersion` is no longer used)
- Jetpack Compose app (or host screen using Compose)
- Network permissions in host manifest

Host `AndroidManifest.xml` minimum:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

If using push on Android 13+:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Installation

### Option A: JitPack (recommended)

1. Add JitPack repository in project `settings.gradle.kts`:

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

2. Add dependency in app module:

```kotlin
dependencies {
    implementation("com.github.dappros.ethora-sdk-android:ethora-component:<version>")
}
```

Use a release tag or commit SHA for `<version>`.

### Option B: Source module integration

Copy these folders into your project root:

- `ethora-component`
- `chat-core`
- `chat-ui`

Then:

```kotlin
// settings.gradle.kts
include(":ethora-component")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":ethora-component"))
}
```

## Host App Setup

Initialize the SDK once per app process, preferably from `Application.onCreate`,
before rendering `Chat(...)` or starting the background bootstrap:

```kotlin
import android.app.Application
import com.ethora.chat.EthoraChatSdk

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EthoraChatSdk.initialize(this)
    }
}
```

`EthoraChatSdk.initialize(...)` is idempotent. Calling it defensively more than
once in the same process is safe, but do not make Activity or Composable
lifecycle own SDK persistence setup. Android may destroy and recreate an
Activity while keeping the process alive, and persistence should remain
process-scoped.

If you have an existing integration that manually initializes
`RoomStore.initialize(...)`, `UserStore.initialize(...)`, and
`MessageStore.initialize(...)`, those calls remain supported and idempotent.
New integrations should prefer the single initializer above.

### Pre-loading data before the Chat tab opens

If your host shell (Activity, Service, or Application) needs the unread count
before the user has ever opened the chat tab — or if your chat screen is lazily
instantiated and may not mount for a while — call
`EthoraChatBootstrap.initializeAsync` right after `EthoraChatSdk.initialize(...)`:

```kotlin
import android.app.Application
import com.ethora.chat.EthoraChatBootstrap
import com.ethora.chat.EthoraChatSdk
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.XMPPSettings

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EthoraChatSdk.initialize(this)

        val config = ChatConfig(
            appId = "YOUR_APP_ID",
            baseUrl = "https://api.your-domain.com/v1",
            customAppToken = "JWT <YOUR_APP_TOKEN>",
            xmppSettings = XMPPSettings(
                xmppServerUrl = "wss://xmpp.your-domain.com/ws",
                host = "xmpp.your-domain.com",
                conference = "conference.xmpp.your-domain.com"
            ),
            jwtLogin = JWTLoginConfig(token = "<USER_JWT>", enabled = true)
        )
        EthoraChatBootstrap.initializeAsync(applicationContext, config)
    }
}
```

Once the bootstrap finishes, `RoomStore.rooms` and all unread APIs reflect real
server state — without the `Chat` composable ever mounting. The same config and
XMPP socket are reused when the user eventually opens the chat tab, so no second
connection is opened. If the user later leaves the chat tab, the shared
bootstrap socket remains alive until explicit logout or
`EthoraChatBootstrap.shutdown()`, so `EthoraChatBootstrap.addUnreadListener(...)`
can keep receiving unread changes while the `Chat` UI is unmounted.

**Config validation:** if `baseUrl` or `xmppSettings` is missing,
`EthoraChatBootstrap.initialize` aborts immediately, sets
`ChatConnectionStatus.ERROR` with a descriptive message, and never attempts an
XMPP connection. It will not fall back to any built-in server.

## Quick Start

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.fillMaxSize
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.XMPPSettings

@Composable
fun ChatScreen() {
    val config = ChatConfig(
        appId = "YOUR_APP_ID",
        baseUrl = "https://api.your-domain.com/v1",
        customAppToken = "JWT <YOUR_APP_TOKEN>",
        disableRooms = false,
        chatHeaderSettings = ChatHeaderSettingsConfig(),
        xmppSettings = XMPPSettings(
            xmppServerUrl = "wss://xmpp.your-domain.com/ws",
            host = "xmpp.your-domain.com",
            conference = "conference.xmpp.your-domain.com"
        ),
        jwtLogin = JWTLoginConfig(
            token = "<USER_JWT>",
            enabled = true
        )
    )

    Chat(
        config = config,
        modifier = Modifier.fillMaxSize()
    )
}
```

## Authentication Modes

Current Android `Chat(...)` init order:

1. `user` param in `Chat(config, user = ...)`
2. `config.userLogin` (if enabled)
3. `config.jwtLogin` (if enabled)
4. persisted JWT token
5. `defaultLogin` placeholder (no built-in email/password UI in SDK)

### JWT login (recommended)

```kotlin
ChatConfig(
    jwtLogin = JWTLoginConfig(token = userJwt, enabled = true)
)
```

### Pre-authenticated user

```kotlin
ChatConfig(
    userLogin = UserLoginConfig(enabled = true, user = user)
)
```

## Single Room vs Multi Room

### Single room mode

```kotlin
ChatConfig(disableRooms = true)
```

Then pass room JID:

```kotlin
Chat(config = config, roomJID = "roomname@conference.example.com")
```

Behavior:

- SDK opens room directly.
- Header back button is hidden automatically in single-room flow.

### Multi-room mode

```kotlin
ChatConfig(disableRooms = false)
```

Room list is shown first, then chat room screen.

## Public APIs for Host UI

### Unread state

Compose:

```kotlin
import com.ethora.chat.useUnread

val unread = useUnread(maxCount = 99)
// unread.totalCount: Int
// unread.displayCount: String (e.g. "99+")
```

Kotlin / Java UI outside Compose:

```kotlin
import com.ethora.chat.EthoraChatBootstrap
import kotlinx.coroutines.flow.Flow

// Boolean: true whenever any room has at least one unread message.
val hasUnreadFlow: Flow<Boolean> = EthoraChatBootstrap.hasUnread()

val registration = EthoraChatBootstrap.addUnreadListener { hasUnread ->
    // toggle native tab dot / icon state
}

registration.close()
```

Java (from Activity, Service, or any non-Compose context):

```java
// Register — listener receives boolean (true = at least one unread, false = none).
AutoCloseable reg = EthoraChatBootstrap.addUnreadListener(hasUnread -> updateChatBadge(hasUnread));

// Unregister (e.g. in onDestroy or on logout)
reg.close();
```

The boolean shape matches typical host integrations — a tab dot, an icon
state-list, or a setVisibility — that only need "is there anything to see"
rather than a precise number. If you do need the actual count from outside
Compose, observe `RoomStore.rooms` directly and sum `unreadMessages`.

Observe whether the background bootstrap has finished:

```kotlin
// StateFlow<Boolean> — true once EthoraChatBootstrap.initialize() completes
EthoraChatBootstrap.isInitialized
```

### Connection state + reconnect

```kotlin
import com.ethora.chat.reconnectChat
import com.ethora.chat.useConnectionState

val connection = useConnectionState()
// connection.status: OFFLINE | CONNECTING | ONLINE | DEGRADED | ERROR
// connection.reason: String?
// connection.isRecovering: Boolean

reconnectChat()
```

## ChatConfig Reference

`ChatConfig` contains many fields for cross-platform parity. Not every field is fully wired in this Android package version.

`baseUrl` and `xmppSettings` (with `xmppServerUrl`, `host`, `conference`) are required by the SDK itself — without them HTTP and XMPP cannot be wired. If any of these is absent or invalid, the `Chat` composable renders a `ConfigErrorScreen` with the validation reason and `EthoraChatBootstrap.initialize` aborts with `ChatConnectionStatus.ERROR`. The SDK does not fall back to any built-in or Ethora-hosted endpoint — a missing `baseUrl` is a hard failure, not a redirect.

`appId` is forwarded as the `x-app-id` header to `/users/login`, `/users/client`, `/chats/my`, and `/push/subscription/{appId}`. Whether it is required depends on your server — set it if your backend enforces it, omit it otherwise.

### Core fields (actively used)

- `appId`, `baseUrl`, `customAppToken`
- `xmppSettings`, `dnsFallbackOverrides`
- `jwtLogin`, `userLogin`, `defaultLogin`
- `disableRooms`, `defaultRooms`
- `disableHeader`, `disableMedia`
- `chatHeaderSettings.roomTitleOverrides`
- `chatHeaderSettings.chatInfoButtonDisabled`
- `chatHeaderSettings.backButtonDisabled`
- `colors`, `bubleMessage`, `backgroundChat`
- `disableProfilesInteractions`
- `eventHandlers`, `onChatEvent`, `onBeforeSend`
- `customComponents`
- `initBeforeLoad` — when `true`, the SDK runs the web-parity bootstrap (user fetch → rooms fetch → XMPP connect → private-store sync → per-room history preload) so `useUnread()` reports real counts before the `Chat` composable mounts. Drive it via `EthoraChatProvider` (wrap your app root) or `EthoraChatBootstrap.initializeAsync(context, config)` from `Application.onCreate`.
- `retryConfig` — `RetryConfig(autoRetry: Boolean = false, maxAttempts: Int = 3)`. Controls whether failed text/media sends are silently retried in the background. **Default is `autoRetry = false`** — failed messages stay in the "Sending failed. Tap to retry or delete." state until the user acts. Manual user-initiated retry via the message context menu is always allowed regardless of this flag. Pass `RetryConfig(autoRetry = true)` to restore the legacy silent-retry behaviour.

### Fields present but not guaranteed as active behavior

These exist in model/API for parity, but Android behavior may be partial or no-op depending on release:

- `googleLogin`, `customLogin`
- `chatHeaderBurgerMenu`, `forceSetRoom`, `setRoomJidInPath`
- `disableRoomMenu`, `disableRoomConfig`, `disableNewChatButton`
- `customRooms`, `roomListStyles`, `chatRoomStyles`
- `headerLogo`, `headerMenu`, `headerChatMenu`
- `refreshTokens`, `translates`
- `disableUserCount`, `clearStoreBeforeInit`, `disableSentLogic`, `newArch`, `qrUrl`
- `secondarySendButton`, `enableRoomsRetry`, `chatHeaderAdditional`
- `botMessageAutoScroll`, `messageTextFilter`, `whitelistSystemMessage`, `customSystemMessage`
- `disableTypingIndicator`, `customTypingIndicator`
- `blockMessageSendingWhenProcessing`, `disableChatInfo`
- `useStoreConsoleEnabled`, `messageNotifications`

If you need guaranteed support for one of these parity fields, validate against your target SDK tag/commit and test in your integration.

## Event and Send Interception

### Send interception

```kotlin
import com.ethora.chat.core.config.OutgoingSendInput
import com.ethora.chat.core.config.SendDecision

val config = ChatConfig(
    onBeforeSend = { input: OutgoingSendInput ->
        val blocked = input.text?.contains("forbidden-word", ignoreCase = true) == true
        if (blocked) SendDecision.Cancel else SendDecision.Proceed(input)
    }
)
```

### Event stream

```kotlin
import com.ethora.chat.core.config.ChatEvent

val config = ChatConfig(
    onChatEvent = { event ->
        when (event) {
            is ChatEvent.MessageSent -> { /* analytics */ }
            is ChatEvent.MessageFailed -> { /* observability */ }
            is ChatEvent.ConnectionChanged -> { /* host banner */ }
            else -> Unit
        }
    }
)
```

`ChatEvent` types include message sent/failed/edited/deleted, reaction, media upload result, connection state changes.

## Custom UI Components

Provide custom composables through `customComponents` to override:

- message rendering
- message actions
- input area
- room list item
- scrollable area/day separator/new message label

See `com.ethora.chat.core.models.CustomComponents` for exact signatures.

## Push Notifications (FCM)

SDK handles subscription flows, but host app must provide Firebase setup and token lifecycle.

### 1. Firebase files and plugin

- Add `google-services.json` to your app module.
- Apply `com.google.gms.google-services` plugin in host app.

### 2. Register Firebase messaging service

Create a service extending `FirebaseMessagingService`, then forward token and JID payload:

```kotlin
PushNotificationManager.setFcmToken(token)
PushNotificationManager.setPendingNotificationJid(jid)
```

### 3. Initialize push manager

Call once at app start:

```kotlin
PushNotificationManager.initialize(context)
```

### 4. Runtime permission (Android 13+)

Request `POST_NOTIFICATIONS` permission from host app.

Notes:

- `Chat(...)` consumes `PushNotificationManager.fcmToken` and subscribes backend/rooms when user + XMPP are ready.
- Opening notification with `notification_jid` allows SDK to navigate to the room when rooms are loaded.

## Persistence and Offline Behavior

- Rooms/user/tokens persisted via DataStore (`ChatPersistenceManager`).
- Messages persisted in Room DB (`chat_database`, table `messages`) via `MessageStore` + `MessageCache`.
- Unsent media/file messages are persisted separately from chat history via `PendingMediaSendQueue`.
  - queued items keep their optimistic message visible
  - upload/XMPP failures show an inline warning with Retry/Delete actions
  - uploaded payloads are reused when only the XMPP send step failed
  - app-private pending files are removed after send success, user discard, or logout cleanup
- **Failed-send behaviour is governed by `RetryConfig`** (see [ChatConfig Reference](#chatconfig-reference)):
  - `autoRetry = false` (default): a failed text or media send is marked permanently failed immediately. The bubble shows "⚠ Sending failed. Tap to retry or delete." and the failure persists across reconnects until the user explicitly retries or deletes. The `Message.sendFailed: Boolean?` flag carries this state.
  - `autoRetry = true`: silent background retries up to `maxAttempts`, with exponential backoff for media. After the limit, the same persistent failed state takes over.
  - Manual user retry via the message context menu is always available (Copy + Retry + Delete) regardless of `autoRetry`. Editing is intentionally hidden for unsent messages — they were never on the server.
- Text sends attempted while offline stay visible as failed bubbles so users can retry or delete them instead of losing the draft silently. Deleting a never-sent message (pending or send-failed) removes it locally only, no XMPP delete is dispatched.
- Combo send: when `ChatInput` has both an attachment and text staged, one Send tap dispatches **two messages** (media first, then text). Each appears as an optimistic bubble immediately and confirms or fails independently.
- Loader behavior:
  - cache-first rooms/messages for fast startup
  - API refresh for rooms
  - initial XMPP history load per room
  - incremental sync after reconnect
- DNS fallback map supported via `dnsFallbackOverrides` for emulator/network edge cases. Only explicit host-provided overrides are used — there is no built-in fallback to any Ethora-hosted server.

## Logout

Use public service:

```kotlin
import com.ethora.chat.core.ChatService

ChatService.logout.performLogout()
```

Optional callback:

```kotlin
import com.ethora.chat.core.service.LogoutService

LogoutService.setOnLogoutCallback {
    // navigate to logged-out host screen
}
```

## Troubleshooting

### Room list empty / unread always 0

- Ensure stores are initialized before rendering `Chat(...)`.
- Ensure user is authenticated (`jwtLogin` or `userLogin`).
- Ensure `baseUrl`, `appId`, and token values are valid.

### Chat stuck offline

- Verify `xmppSettings` (`xmppServerUrl`, `host`, `conference`).
- Confirm websocket endpoint reachable from device/emulator.
- Use `dnsFallbackOverrides` if DNS resolution fails in emulator.

### Push not delivered

- `google-services.json` package name must match host `applicationId`.
- Ensure FCM token is received and forwarded to `PushNotificationManager.setFcmToken`.
- Ensure notification permission is granted (Android 13+).

### Upload/auth 401 issues

- Ensure user token and refresh token are valid.
- Ensure `customAppToken` is set for your backend app.

### File/PDF send stays pending

- The SDK keeps unsent media visible and offers Retry/Delete on failed bubbles.
- Check `useConnectionState()` and XMPP logs if items remain failed.
- If a queued local file was deleted by host cleanup, the message can be discarded by the user.

### PDF preview fails but download works

- Current builds use native `PdfRenderer`; no hosted PDF.js page is required.
- Confirm the file URL returns valid PDF bytes and is reachable from the device.

## Production Checklist

- Always provide your own `baseUrl`, `appId`, `xmppSettings`, and production token values.
- The SDK does not redirect to built-in Ethora endpoints when configuration is missing.
- Pin SDK dependency to a tag/commit you have tested.
- Add host analytics via `onChatEvent`.
- Add host moderation/compliance hooks via `onBeforeSend`.
- Validate push flow end-to-end on real devices.

---

If you need this README split into docs per audience (`integration`, `config reference`, `push`, `migration`), keep this as root overview and move deep dives into `docs/`.
