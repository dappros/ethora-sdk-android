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
    implementation("com.github.dappros:ethora-sdk-android:<version>")
}
```

Use a release tag (e.g. `v1.0.31`) or commit SHA for `<version>`.

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

### SDK lifecycle

The SDK has three concentric lifecycles. Mixing them up is the most common
source of "duplicate DataStore" errors and disappearing unread callbacks, so
this section spells out who owns what.

**1. Process scope — `EthoraChatSdk` (persistence, stores).** All of
`RoomStore`, `UserStore`, `MessageStore`, `MessageCache`, `LocalStorage`, the
`PendingMediaSendQueue`, `ScrollPositionStore` and `PushNotificationManager`
are process-wide singletons backed by a single `DataStore<Preferences>` per
file. They must be initialized exactly once per process and must use the
**application context**:

| Where to initialize | OK? |
|---|---|
| `Application.onCreate()` | ✅ recommended |
| First Activity's `onCreate()` *as a fallback*, with `applicationContext` | ⚠️ tolerated — `EthoraChatSdk.initialize` is idempotent — but Activity recreation re-runs `onCreate`, so this only works because the second call short-circuits |
| Per-Activity setup with the Activity context | ❌ will eventually create a duplicate `DataStore` and throw `IllegalStateException: There are multiple DataStores active for the same file` |
| Inside a Composable (`LaunchedEffect`, etc.) | ❌ same as above, plus it ties persistence setup to a recomposition you do not control |

`EthoraChatSdk.initialize(...)` is `@Synchronized` and guarded by a
`@Volatile initialized` flag, so calling it again in the same process is a
cheap no-op. The legacy per-store `RoomStore.initialize(...)` /
`UserStore.initialize(...)` / `MessageStore.initialize(...)` calls remain
supported and are individually idempotent — they are kept for backwards
compatibility, but new integrations should use `EthoraChatSdk.initialize`.

**2. Session scope — `EthoraChatBootstrap` (XMPP client, unread listeners).**
The shared XMPP socket and the unread-listener registry live on
`EthoraChatBootstrap`, not on the `Chat` composable. A typical app shape:

```kotlin
EthoraChatSdk.initialize(applicationContext)            // step 1, once
EthoraChatBootstrap.initializeAsync(appCtx, chatConfig) // step 2, per session
val reg = EthoraChatBootstrap.addUnreadListener { hasUnread ->
    updateBadge(hasUnread)
}
```

The unread listener fires regardless of whether the `Chat` composable is on
screen. The only caller responsible for that lifecycle is the host — when
you no longer need the listener (e.g. on logout) call `reg.close()`.

**3. UI scope — the `Chat` composable.** The composable consumes
`EthoraChatBootstrap`'s shared client when one is available; if it is not
available it falls back to creating a connection of its own. On dispose, the
composable disconnects **only the client it created itself**. The
bootstrap-owned client is left running so unread callbacks continue to fire
while the chat tab is unmounted. This decision is made automatically — there
is no `disconnectOnDispose` flag to set — and is implemented in
`ChatXMPPClientOwnership.shouldDisconnectOnDispose`.

#### Logout / shutdown

To tear down a session — for example on user-driven logout, or in tests
between cases — call:

```kotlin
EthoraChatSdk.shutdown()
```

`shutdown()`:
- disconnects the shared bootstrap XMPP client and clears it,
- resets the `InitBeforeLoadFlow` and `MessageLoader` sync flags so the next
  login re-runs the first-pass history preload,
- clears the cached fallback client in `XMPPClientRegistry`,
- flips `EthoraChatSdk`'s `initialized` flag so a subsequent
  `EthoraChatSdk.initialize(...)` re-runs the real setup.

`shutdown()` does **not** delete persisted data: the Room database, DataStore
preferences, encrypted token storage, pending-media files and scroll
positions all survive. This is intentional — pending offline messages and
saved tokens must outlive a logout/login round-trip on the same device.

If you need a fully-awaited teardown (e.g. before exiting a test), use the
suspend variant from a coroutine:

```kotlin
EthoraChatBootstrap.shutdownBlocking()
EthoraChatSdk.shutdown()  // flips the initialized flag
```

For a clean wipe of persisted data the host app is responsible for clearing
its own storage (e.g. `context.getSharedPreferences(...)`,
`context.deleteDatabase(...)`); the SDK does not expose a destructive "wipe
all" entry point because it cannot tell what part of that state is yours and
what part is shared with another logged-in user.

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

## Testing

The SDK uses a **two-layer testing strategy**, with each layer pinned
to the codebase it tests so changes ship in the same PR as the test
that exercises them.

This Android SDK is one of four runtime targets that share a single
selector contract — Compose `testTag` strings here match SwiftUI
`accessibilityIdentifier` strings on iOS and `data-testid` attributes
on Web, so a Maestro YAML flow drives all three platforms by the same
ID. See [Cross-platform testing overview](#cross-platform-testing-overview)
at the end of this section.

### Quickstart — run the tests before shipping a change

Always run these two before opening a PR. If either fails, your
change either broke an existing contract or your new test caught a
real bug — investigate before merging.

```bash
# 1. JVM unit tests — fast (~30s), no emulator needed.
#    Covers reducers, parsers, state machines, formatters, persistence
#    caps, and the LogoutService cross-store cleanup contract.
./gradlew :ethora-component:testDebugUnitTest

# 2. Compose UI tests on an emulator — slower (~1min), needs a running
#    AVD. Covers the chat-component visual contract.
./gradlew :ethora-component:connectedDebugAndroidTest
```

**Compose UI test environment requirement.** Use an emulator at **API
34 (Android 14)** or below. API 35/36 ship a changed
`InputManager.getInstance` signature that the current Compose UI Test
infrastructure (`androidx.compose.ui:ui-test-junit4`) hasn't caught up
with — tests crash with `NoSuchMethodException` before assertions
run. JVM unit tests are unaffected.

To set up an AVD:

1. Android Studio → **Tools → Device Manager** → **+ Create Device**
2. Pick any Pixel profile (Pixel 6 is fine)
3. System Image: **Android 14 / API 34 / Google APIs** (arm64-v8a on
   Apple Silicon, x86_64 on Intel). Download if needed (~700 MB).
4. Name it anything, finish.

Boot the AVD before running the connected tests:

```bash
# Replace with your AVD name from Device Manager
$ANDROID_HOME/emulator/emulator -avd Pixel_6_API_34 -no-snapshot-load &
adb wait-for-device
./gradlew :ethora-component:connectedDebugAndroidTest
```

### Layer 1 — Unit + Compose UI tests (this repo)

`chat-core/` and `chat-ui/` are not standalone Gradle modules — their
sources aggregate into `:ethora-component` via the `sourceSets` block
in `ethora-component/build.gradle.kts`. Tests live under
`ethora-component/src/test/` (JVM unit) and
`ethora-component/src/androidTest/` (Compose UI), matching standard
Android conventions and removing the need for custom srcDir wiring.

| Where | What | Run with |
|-------|------|----------|
| `ethora-component/src/test/` | Pure-JVM unit tests — JID parsing, message serializers, store reducers, networking helpers, persistence caps, LogoutService | `./gradlew :ethora-component:testDebugUnitTest` |
| `ethora-component/src/androidTest/` | Compose UI tests using `androidx.compose.ui.test` — render a composable in isolation, drive it with callbacks, assert behavior. Requires a connected emulator/device (API 26-34) | `./gradlew :ethora-component:connectedDebugAndroidTest` |

The Compose UI tests are hermetic — no network, no XMPP, no FCM.
End-to-end flows that need a real server go in Layer 2.

`ethora-component/src/androidTest/java/com/ethora/chat/ui/components/ChatInputTest.kt`
is the canonical example to copy when adding a new Compose UI test.

#### Test inventory

As of the current main, **107 JVM unit tests + 19 Compose UI tests = 126 tests** across 13 files. All pass under
`./gradlew :ethora-component:testDebugUnitTest` and
`./gradlew :ethora-component:connectedDebugAndroidTest`.

**JVM unit tests:**

| Test class | Tests | What it covers |
|------------|-------|----------------|
| `RoomStoreTest` | 35 | Reducer semantics: add/update/upsert/remove, presenceReady stickiness, `setCurrentRoom` transitions A→B→A, per-room loading isolation, `lastViewedTimestamp` bump on switch, unread counter math (active=0, lastViewed=0 counts all, excludes own/pending/system, caps at 99 with `unreadCapped`, lastViewed boundary), multi-room concurrent receive (current pointer + lastMessage isolation), pending count, composing per-room |
| `MessageStoreTest` | 18 | `setMessagesForRoom`/`getMessagesForRoom` round-trip + per-room isolation, `addMessage` new-append, MAM-replay idempotency, pending-merge bidirectional ID matching (load-bearing reconciliation between optimistic UUID and server-assigned id), rapid-send order, cross-room independence, bulk `addMessages` dedup + chronological order, tombstone-on-delete, persistence cap at 100 |
| `TimestampUtilsTest` | 15 | s/ms/µs/ns ladder, ISO-8601 + XEP-0091 parsing, embedded-digit extraction, null/Date/Number/String dispatch, zero-clamping on invalid input |
| `XmppXmlUtilsTest` | 14 | `extractDataElement` (self-closing vs open-close, malformed), `extractAttribute` (double/single/unquoted; entity decoding for `&amp;`/`&lt;`/`&gt;`/`&quot;`/`&apos;` — anchored on the signed-URL thumbnail regression) |
| `PendingMediaSendQueueTest` | 5 | Queue add/remove/clear, persistence across instantiations |
| `ChatConfigurationTest` | 4 | Fail-loud on missing `baseUrl`, missing `xmppSettings`, non-websocket `xmppServerUrl` scheme; configured values returned unchanged |
| `UnreadObserverTest` | 3 | Aggregate `EthoraChatBootstrap.hasUnread()` flow + Java listener registration |
| `ChatXMPPClientOwnershipTest` | 3 | Shared bootstrap client not disconnected on Chat dispose; non-shared is; missing client is a no-op |
| `SingleRoomSupportTest` | 3 | Single-room mode constraints |
| `LogoutServiceTest` | 3 | `performLogout` clears UserStore + RoomStore + MessageStore + invokes callback; idempotent re-entry on already-empty session |
| `FileSizeFormatterTest` | 2 | Byte → human string conversion (B/KB/MB/GB) |
| `DnsFallbackTest` | 1 | Override map lookup |
| `LogExportFormatterTest` | 1 | Log line serialisation shape |

**Compose UI tests:**

| Component | Tests | Asserts |
|-----------|-------|---------|
| `ChatInput` | 4 | Send callback fires on tap; empty input → disabled Send + no callback; `editText` prop pre-populates field; reply preview + Cancel callback |
| `LogsView` | 2 | Filter field + log entries render; filter input hides non-matching entries |
| `MessageBubble` | 4 | Outgoing body text; incoming bubble shows author + body; deleted message shows tombstone copy (not original body); send-failed state composes |
| `MessageContextMenu` | 9 | Visible/invisible short-circuits; own-message Copy/Edit/Delete; received-only Copy; pending + `onResend` → Retry replaces Edit; auto-timer sendFailed → Retry; missing `onResend` falls back to Edit; tap Copy fires both `onCopy` and `onDismiss`; regression guard for exactly 3 menu items |

**Test dependencies** (declared in `gradle/libs.versions.toml` and
`ethora-component/build.gradle.kts`):

- `junit:junit` — base test runner
- `org.mockito:mockito-core` 5.5.0 — inline mocking by default
- `org.mockito.kotlin:mockito-kotlin` 5.4.0 — Kotlin DSL +
  `onBlocking` for suspend-function stubbing
- `kotlinx-coroutines-test` — `runTest`, `UnconfinedTestDispatcher`,
  `Dispatchers.setMain` / `resetMain`. Required for async tests that
  bounce through `Dispatchers.Main` + `Dispatchers.IO` (LogoutService,
  persistence cap)

**Gradle build-test wiring** that's load-bearing for the test layer
(`ethora-component/build.gradle.kts`):

- `testOptions.unitTests.isReturnDefaultValues = true` — JVM unit
  tests don't run on an Android device, so calls to `android.util.Log`
  in production code paths would throw "Method not mocked". This flag
  returns zero-values for any `android.*` API the SUT touches,
  silencing logging in tests.
- `packaging.resources.excludes` — strips duplicate `META-INF`
  manifests from `androidTest` merge so
  `connectedDebugAndroidTest` doesn't fail in
  `mergeDebugAndroidTestJavaResource`.

#### Gaps still to cover

File an issue + a test in the same PR when you tackle one:

- `RoomListView` — search behavior, active-room highlight, badge
  counts
- `FullScreenImageViewer` — zoom + pan + close
- `PDFViewer` — page navigation + render-on-low-memory fallback
- `ChatInfoScreen` — participants list, leave-room flow
- `chat-core` `XMPPClient` state machines — BIND-result handling,
  MAM subscription, reconnect on socket drop (testable with a stubbed
  transport)
- `chat-core` send-failed timeout path in
  `MessageStore.schedulePendingTimeout` (uses real
  `kotlinx.coroutines.delay` — needs a `TestScheduler` override on
  the private `pendingScope`)
- LogoutService → XMPPClient.disconnect() call assertion (currently
  the tests exercise the null-client "skipping disconnect" branch
  only; mocking the final `XMPPClient` class is doable with mockito
  inline + IO coordination but adds surface beyond the test value)

The cross-platform [QA Scenarios catalog](https://github.com/dappros/ethora/blob/main/QA_SCENARIOS.md)
in the monorepo lists 12 failure-mode clusters distilled from field
reports and maps each to its recommended test layer — useful when
scoping the next round of coverage.

#### Current Compose UI coverage

| Component | Test | Asserts |
|-----------|------|---------|
| `ChatInput` | `rendersInputAndFiresCallbackOnSend` | Type → tap Send → `onSendMessage` callback fires with the typed text |
| `ChatInput` | `emptyInputShowsSendIconWithoutFiringCallback` | Empty field shows a disabled Send icon; tap is a no-op |
| `ChatInput` | `editModePrePopulatesText` | `editText="..."` prop is rendered in the field on first composition |
| `ChatInput` | `replyPreviewShowsAndCancelFiresCallback` | `replyingToMessage` → preview body visible → "Cancel reply" fires `onReplyCancel` |
| `LogsView` | `rendersFilterFieldAndLogEntries` | Entries pushed via `LogStore.info(...)` render with the "Filter logs" field visible |
| `LogsView` | `queryFilterHidesNonMatchingEntries` | Typing into the filter hides non-matching entries |
| `MessageBubble` | `rendersBodyText` | Outgoing bubble renders its body text |
| `MessageBubble` | `rendersAuthorNameForIncomingMessage` | Incoming bubble (`isUser=false`) shows author + body |
| `MessageBubble` | `rendersDeletedTombstone` | Bubble composes without crashing for `isDeleted=true` |
| `MessageBubble` | `rendersSendFailedState` | Bubble composes without crashing for `sendFailed=true` |
| `MessageContextMenu` | `rendersNothingWhenInvisible` | `visible=false` short-circuits — no Copy/Edit/Delete labels in composition |
| `MessageContextMenu` | `rendersNothingForDeletedMessage` | `isDeleted=true` short-circuits even with `visible=true` |
| `MessageContextMenu` | `ownMessageShowsCopyEditDelete` | `isUser=true` + non-pending → Copy, Edit, Delete; no Retry |
| `MessageContextMenu` | `receivedMessageShowsCopyOnly` | `isUser=false` → Copy only, no Edit/Delete |
| `MessageContextMenu` | `pendingOwnMessageWithResendHandlerOffersRetry` | `pending=true` + `onResend!=null` → Retry replaces Edit |
| `MessageContextMenu` | `sendFailedOwnMessageOffersRetry` | `sendFailed=true` → Retry replaces Edit (auto-timer path) |
| `MessageContextMenu` | `pendingMessageWithoutResendHandlerOffersEditNotRetry` | Missing `onResend` → falls back to Edit |
| `MessageContextMenu` | `tappingCopyFiresOnCopyAndOnDismiss` | Tap Copy → both `onCopy` and `onDismiss` callbacks fire (auto-close) |
| `MessageContextMenu` | `ownMessageRendersExactlyThreeMenuItems` | Regression guard: own-message menu has exactly 3 items (Copy, Edit, Delete) |

##### chat-core unit tests

Pure-JVM, no emulator. Run with `./gradlew :chat-core:test`.

| Module | Test class | Asserts |
|--------|------------|---------|
| `chat-core` | `TimestampUtilsTest` | 14 tests covering the s/ms/µs/ns ladder, ISO-8601 + XEP-0091 string parsing, embedded-digit extraction, null/Date/Number/String type dispatch, and zero-clamping on invalid input |
| `chat-core` | `XmppXmlUtilsTest` | 14 tests covering `extractDataElement` (self-closing vs open-close, malformed input) and `extractAttribute` (double-quoted, single-quoted, unquoted; missing attribute; entity decoding for `&amp;` / `&lt;` / `&gt;` / `&quot;` / `&apos;`). Anchored on the historical bug where signed-URL thumbnails broke because `&amp;` wasn't decoded back to `&` in MAM-replayed messages |
| `chat-core` | `RoomStoreTest` | 15 tests covering `setRooms` (full replace), `addRoom` (append vs merge by id / jid), `updateRoom` (replace + `presenceReady` stickiness — incoming `false` doesn't clear an already-`true` flag, regression-guarded), `removeRoom` (by id or jid; clears `currentRoom` when active room is removed), `setCurrentRoom`, `getRoomById` / `getRoomByJid`, `upsertRoom`, `clear` |
| `chat-core` | `MessageStoreTest` | 11 tests covering `setMessagesForRoom` / `getMessagesForRoom` (per-room isolation), `addMessage` new-append (returns `false`), `addMessage` non-pending duplicate dropped (MAM-replay idempotency), `addMessage` pending-merge bidirectional ID matching (incoming `id` vs existing `xmppId` AND existing `id` vs incoming `xmppId` — the load-bearing reconciliation between optimistic UUID and server-assigned id), `clearMessagesForRoom`, `clear` |

**Gaps** still to cover at this layer (file an issue + a test in the
same PR when you tackle one):

- `RoomListView` — search behavior, active-room highlight, badge counts
- `FullScreenImageViewer` — zoom + pan + close
- `PDFViewer` — page navigation + render-on-low-memory fallback
- `ChatInfoScreen` — participants list, leave-room flow
- `chat-core` `XMPPClient` state machines — BIND-result handling, MAM
  subscription, reconnect on socket drop (testable with a stubbed
  transport)
- `chat-core` send-failed timeout path in `MessageStore.schedulePendingTimeout`
  (uses `kotlinx.coroutines.delay` — needs a coroutine TestScheduler)
- Tombstone / failed-state explicit string assertions on `MessageBubble`
  (TODOs in `MessageBubbleTest.kt` flag where to tighten once the SDK
  exposes stable strings)

### Layer 2 — End-to-end smoke flows (`ethora-sample-android`)

Maestro YAML scenarios that drive the sample app on a real
emulator/device against `chat-qa.ethora.com`: login → list rooms → send
text → receive text → reconnect → push intent → logout. Live in
[`ethora-sample-android/.maestro/`](https://github.com/dappros/ethora-sample-android/tree/main/.maestro)
because they need a built APK, not SDK source.

These run on the sample's CI on every release tag of the SDK — that's
the gate that catches integration regressions like config drift,
preset URL breakage, or feature parity gaps with iOS/Web.

### Adding a test for a fix or new feature

- **Behavior bug in `chat-core` or `chat-ui`** → add a unit / Compose
  UI test in this repo, in the same PR as the fix.
- **Integration bug** (something the SDK exposes but the sample
  consumes) → add a Maestro flow in `ethora-sample-android/.maestro/`,
  in a paired PR to that repo.
- **Cross-platform parity gap** → add the matching test to all three
  (Android Maestro, iOS Maestro, Web Playwright). The selector
  contract below makes the test bodies near-identical across
  platforms.

### Cross-platform testing overview

Four runtime targets, one selector contract. Same test intent runs
against any of them via Maestro (mobile) or Playwright (web).

| Layer 1 (hermetic) | Layer 2 (E2E) |
|--------------------|----------------|
| `ethora-sdk-android` — Compose UI tests in `chat-ui/src/androidTest/` (this repo) | `ethora-sample-android/.maestro/` — 19 Maestro flows on Android emulator |
| `ethora-sdk-swift` — XCTest in `Tests/XMPPChatCoreTests/` + `accessibilityIdentifier` markers in `XMPPChatUI/` | `ethora-sample-swift/.maestro/` — same 19 Maestro flows on iOS Simulator |
| `ethora-chat-component` — Vitest + RTL in `src/**/*.test.tsx` with `data-testid` attrs | `ethora-app-reactjs/tests/e2e/` — Playwright on chromium |

Selector parity (a Maestro `id: "chat_input"` matches all of these):

| String | Android (`*TestTags`) | iOS (`*AccessibilityID`) | Web (`*TestIds`) |
|--------|----------------------|--------------------------|------------------|
| `chat_input` | `ChatInputTestTags.INPUT_FIELD` | `ChatInputAccessibilityID.inputField` | `ChatInputTestIds.inputField` |
| `chat_send_button` | `ChatInputTestTags.SEND_BUTTON` | `ChatInputAccessibilityID.sendButton` | `ChatInputTestIds.sendButton` |
| `chat_attach_button` | `ChatInputTestTags.ATTACH_BUTTON` | `ChatInputAccessibilityID.attachButton` | `ChatInputTestIds.attachButton` |
| `chat_message_image` | `MessageBubbleTestTags.MEDIA_CONTENT` | `MessageBubbleAccessibilityID.mediaContent` | `MessageBubbleTestIds.mediaContent` |
| `rooms_list` | `RoomListViewTestTags.ROOMS_LIST` | `RoomListAccessibilityID.roomsList` | `RoomListTestIds.roomsList` |
| `room_row` | `RoomListViewTestTags.ROOM_ROW` | `RoomListAccessibilityID.roomRow` | `RoomListTestIds.roomRow` |
| `rooms_search_input` | `RoomListViewTestTags.SEARCH_INPUT` | (system search bar, no ID) | `RoomListTestIds.searchInput` |
| `create_room_button` | `RoomListViewTestTags.CREATE_ROOM_BUTTON` | `RoomListAccessibilityID.createRoomButton` | `RoomListTestIds.createRoomButton` |

Changing any value above is a 4-repo change. The cost of that
coupling is the benefit — a renamed tag breaks four CI runs the
same week, not silently rotting in one of them.

## Production Checklist

- Always provide your own `baseUrl`, `appId`, `xmppSettings`, and production token values.
- The SDK does not redirect to built-in Ethora endpoints when configuration is missing.
- Pin SDK dependency to a tag/commit you have tested.
- Add host analytics via `onChatEvent`.
- Add host moderation/compliance hooks via `onBeforeSend`.
- Validate push flow end-to-end on real devices.

---

If you need this README split into docs per audience (`integration`, `config reference`, `push`, `migration`), keep this as root overview and move deep dives into `docs/`.
