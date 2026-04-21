# JitPack Package Features

## Distribution & Setup
- Published via JitPack for quick Gradle-based installation.
- Simple dependency coordinate:
  - `com.github.dappros.ethora-sdk-android:ethora-component:<version>`
- Works with both tagged releases (for stability) and commit hashes (for hotfix pinning).
- No manual source copy required for standard integrations.

## Included SDK Capabilities
- Ready-to-use Android chat component built with Jetpack Compose.
- Bundled chat foundation from the SDK modules:
  - `ethora-component`
  - `chat-core`
  - `chat-ui`
- Single-room and multi-room chat flows supported.
- Config-driven runtime setup (API URL, auth token, XMPP settings, room options).

## Authentication & Connectivity
- JWT-based login support via SDK config.
- XMPP/WebSocket-compatible chat connection settings.
- DNS fallback override support for emulator or constrained network environments.
- Public host-facing connection state API (`useConnectionState`) with retry trigger (`reconnectChat`).

## UX & Product Features
- Customizable chat header behavior and room title overrides.
- Unread counter hook (`useUnread`) for host-app badges and tab indicators.
- Host-app controlled integration flow to fit onboarding and retention UX.
- `useUnread` can be consumed in the outer app layer and hoisted to your own state/view model.
- Rich media UX includes full-screen image, video, and PDF previews with improved gallery-style image browsing.
- Message rendering includes clickable links, markdown formatting, and URL preview cards.

### `useUnread` in outer app (propagation pattern)
- Yes, it can propagate to the outer app.
- `useUnread` reads from SDK `RoomStore` and returns plain values (`totalCount`, `displayCount`), so you can pass them up like any other Compose state.
- Recommended usage: call `useUnread()` in a host composable that wraps your app shell/tabs, then pass `displayCount` to your tab badge/top-level UI.
- If chat is not initialized yet, unread is `0`.

```kotlin
@Composable
fun HostShell() {
    val unread = useUnread(maxCount = 99)
    AppScaffold(chatBadgeText = unread.displayCount)
}
```

## Connectivity / Offline State
- SDK includes reconnect and offline-recovery logic for XMPP/message sync.
- SDK exposes a dedicated host-facing connection state surface (`OFFLINE`, `CONNECTING`, `ONLINE`, `DEGRADED`, `ERROR`).
- Host apps can drive banner/CTA state from connection status and call `reconnectChat()` for manual retry.

## Integration Quality
- Kotlin/Compose-friendly API surface designed for Android apps.
- Production-oriented dependency flow through version pinning.
- Easy upgrades by moving only the dependency version.
- Suitable for MVP shipping with a path to scale.

## Cache & Message Storage
- Messages are cached locally using Android Room database (`chat_database`, `messages` table).
- `MessageStore` persists messages in background and loads persisted messages when opening a room.
- Current behavior keeps and reloads the latest messages per room (last 50 on load path in `MessageStore`).
- Rooms and current room selection are also persisted (via persistence manager), enabling cache-first room rendering.

## Extensibility Hooks
- Structured event stream via `ChatConfig.onChatEvent` (message send/fail/edit/delete/reaction, media upload, connection changes).
- Outgoing send interception via `ChatConfig.onBeforeSend` with proceed/modify/cancel decision.
- UI extension slots for:
  - custom message renderer,
  - custom input composer,
  - custom message actions,
  - custom room list item renderer.
