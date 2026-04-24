# Changelog

All notable changes to this package are documented here. For cross-SDK release notes, see [ethora/RELEASE-NOTES.md](https://github.com/dappros/ethora/blob/main/RELEASE-NOTES.md).

---

## [26.04.24] — JitPack `v1.0.25`

- **New:** Persistent media/file send queue (`PendingMediaSendQueue`) stored separately from cached chat messages; unsent image/video/audio/file/PDF messages stay visible and retry after reconnect or app foreground instead of disappearing.
- **Improved:** Text send responsiveness — the input clears and the optimistic message is added before XMPP presence/socket work, reducing perceived send-button lag.
- **Improved:** Media upload flow preserves selected file names/extensions in app-private `pending_uploads`, including `.pdf`, and reuses uploaded payloads when only the XMPP send step needs retry.
- **Improved:** XMPP recovery resets `reconnectAttempts` after full connection and when the app returns to foreground, allowing recovery after background/foreground transitions.
- **Improved:** Bootstrap and connection reliability: shared XMPP client registry, foreground retry hooks, reconnect delta sync, and safer bootstrap reuse across `EthoraChatProvider` / `Chat` remounts.
- **Improved:** Message history and unread behavior: new-message delimiter support, last-viewed synchronization hardening, and cache/RoomStore reconciliation updates.
- **Fixed:** File/PDF sends could be removed from the UI when upload or XMPP send failed.
- **Fixed:** Received PDFs could download successfully but fail to open inside the in-app PDF viewer.
- **Fixed:** Create-chat room deserialization and room type lower-case JSON handling.
- **Fixed:** XMPP bind/result matching and WebSocket media/data stanza handling edge cases.

## [26.04.23]

- **New:** `initBeforeLoad` config flag now wired — when set, SDK runs full web-parity bootstrap (user → rooms → XMPP → private-store sync → per-room history preload) before `Chat` mounts, so `useUnread()` reflects real server counts on first render
- **New:** `EthoraChatBootstrap.initialize(context, config)` / `.initializeAsync(...)` — imperative bootstrap entry point for calling from `Application.onCreate` ([`779abbd`](https://github.com/dappros/ethora-sdk-android/commit/779abbd))
- **New:** `EthoraChatProvider` composable — wrap your app root to run bootstrap automatically and share the XMPP socket with the downstream `Chat` composable
- **Improved:** `XMPPClient` / `XMPPWebSocketConnection` / `IncrementalHistoryLoader` / `MessageStore` / `RoomStore` reliability rewrite; extracted `TimestampUtils` and `SingleRoomSupport`
- **Improved:** Coil upgraded to 3.4 — package rename `io.coil-kt` → `io.coil-kt.coil3`; added `coil-network-okhttp` artifact (required for remote http(s) image URLs)
- **Improved:** JitPack publishing is now opt-in via `-Ppublish=true` (or `PUBLISH_SDK=true` env) so day-to-day sample-app builds skip the publication banner
- **Build:** Gradle 9.4.1, Kotlin 2.3.0, AGP 8.7.0 ([`c60f19f`](https://github.com/dappros/ethora-sdk-android/commit/c60f19f))
- **Build:** `minSdk` raised to `26`; `compileSdk` / `targetSdk` kept at `34` (JitPack does not ship `platforms;android-37`, and no source references API 37)
- **Build:** Compose Compiler now configured via the `kotlin-compose` plugin (Kotlin 2.0+ no longer uses `composeOptions.kotlinCompilerExtensionVersion`)
- **Sample:** `sample-chat-app/` re-included in the repo via `settings.gradle.kts` `projectDir` redirect — run `./gradlew :sample-chat-app:installDebug` from the SDK root
- **Host migration required:** host app must use Kotlin ≥ 2.3.0, AGP ≥ 8.7.0, Gradle 9.4.1, apply `id("org.jetbrains.kotlin.plugin.compose")`, raise `minSdk` to 26 — see README "Requirements"

## [26.04.21]

Major feature release:

- **New:** URL link previews in messages — new `UrlPreviewStore` renders link cards inline ([`2de87c6`](https://github.com/dappros/ethora-sdk-android/commit/2de87c6))
- **New:** Connection state monitoring — new `ConnectionStore` + `ConnectionHook` expose live connection status to host apps ([`2de87c6`](https://github.com/dappros/ethora-sdk-android/commit/2de87c6))
- **New:** Event dispatcher — new `ChatEvents` + `ChatEventDispatcher` let host apps subscribe to chat lifecycle events ([`2de87c6`](https://github.com/dappros/ethora-sdk-android/commit/2de87c6))
- **Improved:** `ChatRoomView`, `ChatRoomViewModel`, `MessageBubble`, `ChatInput`, `FullScreenImageViewer` updated to support preview / event hooks
- **Docs:** Added feature documentation + Android ↔ iOS platform comparison, restructured README ([`35909da`](https://github.com/dappros/ethora-sdk-android/commit/35909da))
- **Refactored:** Sample app extracted into [`ethora-sample-android`](https://github.com/dappros/ethora-sample-android) — SDK repo no longer tracks `sample-chat-app/` ([`86fd0a2`](https://github.com/dappros/ethora-sdk-android/commit/86fd0a2))
- **Fixed:** Message loader + XMPP websocket edge cases, `ChatRoomView` rendering ([`9998279`](https://github.com/dappros/ethora-sdk-android/commit/9998279))

## [26.04.10]

- **Improved:** `XMPPClient` hardened (213-line update) with expanded `XMPPSettings` ([`97ad445`](https://github.com/dappros/ethora-sdk-android/commit/97ad445))
- **Improved:** `IncrementalHistoryLoader` and `MessageLoader` reliability fixes ([`97ad445`](https://github.com/dappros/ethora-sdk-android/commit/97ad445))

## [26.04.08]

- **New:** SDK playground in sample-chat-app — interactive `MainActivity` for exercising SDK features (621-line addition) ([`fd539c8`](https://github.com/dappros/ethora-sdk-android/commit/fd539c8))

## [26.04.01]

- **Docs:** Added push notification setup instructions to README ([`670c337`](https://github.com/dappros/ethora-sdk-android/commit/670c337))
- **Improved:** `google-services.json` is no longer committed — developers supply their own Firebase config per-project

## [26.03.30]

- **New:** Firebase push notifications wired through sample app — new `EthoraApplication.kt`, `EthoraFirebaseMessagingService.kt`, AndroidManifest entries ([`925b9d8`](https://github.com/dappros/ethora-sdk-android/commit/925b9d8))
- **Improved:** `PushAPI` + `PushNotificationManager` updated for new registration flow

## [26.03.18] — v1.0.0

Major release addressing all client-reported issues and adding key enterprise features:

- **New:** JitPack distribution — install via Gradle dependency instead of manual zip/git ([`68f708f`](https://github.com/dappros/ethora-sdk-android/commit/68f708f))
- **New:** Unread message badge support for chat rooms
- **New:** Single-room mode — set `roomJid` in `EthoraChatConfig` to lock the SDK to one conversation
- **New:** JWT token fields — set user JWT directly so the chat component handles all data fetching internally
- **Improved:** Renamed `devServer` to `xmppServer` in config to avoid confusion in production deployments
- **Improved:** Removed confusing `ethora-cc-android` nested module — single clean repo structure with unified documentation
- **Improved:** Better error handlers and improved first-load performance
- **Fixed:** `/chats/my` returning 401 when using email login — resolved by allowing direct JWT token injection
- **Fixed:** (+) button crash (`PlatformRipple` exception) when `disableHeader: true` — button now hidden cleanly with option to add custom UI
- **Docs:** Consolidated to single documentation file, resolving README vs INSTRUCTIONS discrepancy

## [26.03.02]

- **New:** Push notifications support ([`da0cac6`](https://github.com/dappros/ethora-sdk-android/commit/da0cac6))
- **New:** Media sending — users can now send images and files in chat ([`72a0b2a`](https://github.com/dappros/ethora-sdk-android/commit/72a0b2a))
- **New:** Media viewing — inline image/file preview in messages ([`afa30e1`](https://github.com/dappros/ethora-sdk-android/commit/afa30e1))
- **New:** Message animation effects ([`fa75cf9`](https://github.com/dappros/ethora-sdk-android/commit/fa75cf9))
- **Improved:** Code split for better modularity ([`da0cac6`](https://github.com/dappros/ethora-sdk-android/commit/da0cac6))
- **Improved:** Pagination loader — fixed infinite scroll and "load more" behavior ([`4f67d27`](https://github.com/dappros/ethora-sdk-android/commit/4f67d27))
- **Fixed:** Message animation rendering issues ([`2485565`](https://github.com/dappros/ethora-sdk-android/commit/2485565))
- **Milestone:** Versions v0.7 → v0.8 → v0.9.1 → v1.0 progression ([`87066f7`](https://github.com/dappros/ethora-sdk-android/commit/87066f7)...[`f555462`](https://github.com/dappros/ethora-sdk-android/commit/f555462))
