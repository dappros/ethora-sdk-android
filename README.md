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
- Media messages (image/video/audio/files), full-screen image viewer, PDF/web preview support.
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
- `compileSdk 37`, `targetSdk 37`
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

Before rendering `Chat(...)`, initialize SDK stores (same pattern as sample app):

```kotlin
import android.content.Context
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.push.PushNotificationManager
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore

fun initEthoraSdk(context: Context) {
    val appContext = context.applicationContext
    val persistenceManager = ChatPersistenceManager(appContext)
    val chatDatabase = ChatDatabase.getDatabase(appContext)
    val messageCache = MessageCache(chatDatabase)

    RoomStore.initialize(persistenceManager)
    UserStore.initialize(persistenceManager)
    MessageStore.initialize(messageCache)
    ScrollPositionStore.initialize(appContext)
    MessageLoader.initialize(LocalStorage(appContext))

    // Optional but recommended if push is enabled
    PushNotificationManager.initialize(appContext)
}
```

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

```kotlin
import com.ethora.chat.useUnread

val unread = useUnread(maxCount = 99)
// unread.totalCount: Int
// unread.displayCount: String (e.g. "99+")
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
- Loader behavior:
  - cache-first rooms/messages for fast startup
  - API refresh for rooms
  - initial XMPP history load per room
  - incremental sync after reconnect
- DNS fallback map supported via `dnsFallbackOverrides` for emulator/network edge cases.

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

## Production Checklist

- Replace demo/default endpoints and app tokens.
- Always provide your own `customAppToken` and `appId`.
- Do not rely on default token embedded in `AppConfig` for production.
- Pin SDK dependency to a tag/commit you have tested.
- Add host analytics via `onChatEvent`.
- Add host moderation/compliance hooks via `onBeforeSend`.
- Validate push flow end-to-end on real devices.

---

If you need this README split into docs per audience (`integration`, `config reference`, `push`, `migration`), keep this as root overview and move deep dives into `docs/`.
