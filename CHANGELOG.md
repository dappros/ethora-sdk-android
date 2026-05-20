# Changelog

All notable changes to this package are documented here. For cross-SDK release notes, see [ethora/RELEASE-NOTES.md](https://github.com/dappros/ethora/blob/main/RELEASE-NOTES.md).

---

## [26.05.20] — JitPack `v1.0.41`

- **Fixed:** `addUnreadListener` no longer stays pinned at `hasUnread=false` for badge-listener integrations whose room JID collides with the current user identity. `RoomStore.isOwnMessage` previously fed the raw stanza `xmppFrom` (shape `roomJid/senderResource` for MUC) into `identityCandidates`, which split the room JID into bare + local-part and added them as **sender** identity candidates. When the room's local part shared a string with `currentUser.id` / `xmppUsername` — exactly the Ethora layout where rooms are created as `<creatorId>_<uuid>@conference…` — every incoming MUC stanza was misclassified as "own", `updateUnreadCount` filtered it, `RoomStore.rooms` never re-emitted a delta on `hasUnread()`, and the listener appeared dead. `isOwnMessage` now strips the room prefix and matches only the sender resource segment; `user.id` / `user.xmppUsername` (populated by `XMPPWebSocketConnection.parseAndHandleRealtimeMessage` from `senderJID`) carry the actual ownership signal. A new regression test pins the worst-case `user.id == room.local` collision.
- **Fixed:** Server-initiated XMPP stream termination (e.g. `<stream:error/>` with `conflict` "Replaced by new connection" when the same JID logs in elsewhere, or `<close/>` framing) is now detected and triggers an immediate client-side close so the reconnect loop wakes up. Previously the stanza fell through to `parseAndHandleStanza`, which silently ignored it, leaving the SDK in `AUTHENTICATED` state with a dead socket until OkHttp's idle detection broke the connection ~13 min later — during which the unread listener could never receive new messages because no real socket existed.
- **Fixed:** `onClosing` now acks the server's WebSocket close frame via `webSocket.close(code, reason)` to complete the OkHttp handshake and progress to `onClosed`. Without this the socket sat half-closed for minutes and the reconnect flow never started — same symptom as above for any clean server-initiated disconnect.
- **Fixed:** `ChatRoomView` per-room state (`unreadCount`, `lastMessageCount`, `lastTailId`, `pendingOwnMessageAutoScroll`, `showScrollToBottom`) was held by un-keyed `remember`, so switching rooms inside the same `Chat` composable carried stale tail anchors and counter values into the new room — producing inflated in-room badges (e.g. "23" on a room that has only 2 unread). All five are now keyed by `room.jid` so each room gets a fresh slot on switch.- **Improved:** `ChatService.lifecycle.onChatPaused` and `onChatResumed` now run `RoomStore.recomputeUnreadForAllRooms()` after every visibility transition, instead of relying on the host to call `EthoraChatBootstrap.recomputeUnread()` manually. Closes the failure mode where a message arrived during the transition window — while the active-room shortcut still forced `unread=0` — and never got re-evaluated after `setCurrentRoom(null)`. Same code path the next incoming message would take; cheap, idempotent, safe to call twice. Host integrations no longer need a `try/finally { recomputeUnread() }` belt-and-braces around their `onChatPaused()` calls.
- **Improved:** When `isOwnMessage` returns true, `RoomStoreUnreadDbg` now logs the **matched candidate set** (`→ isOwnMessage=true matched=[...]`) alongside the existing per-message line. Tells you in one log line whether the collision was on `user.id`, `xmppUsername`, or the `xmppFrom` resource segment — so a misconfigured `currentUser` is spotted at `adb logcat -s RoomStoreUnreadDbg:D` without having to guess.
- **Tests:** SDK unit tests pass against the rebuilt artifact (`./gradlew :ethora-component:test`). Added `ChatLifecycleServiceUnreadTest` (3 tests) and extended `UnreadRecomputeTest` with the MUC room/user collision case.

---

## [26.05.15] — JitPack `v1.0.39`

- **New:** `EthoraChatBootstrap.recomputeUnread()` — host-facing safety net that forces an unread recompute across every room from current `MessageStore` state and re-emits on the `hasUnread()` flow. Same code path that incoming messages take (`RoomStore.updateUnreadCount` per room), so the answer is identical to what would arrive on the next real message. Use it when the host suspects the listener is stuck on a stale boolean — e.g. after a manual persistence mutation, an out-of-band message injection, or a race with host-side lifecycle code. Cheap and idempotent.
- **Improved:** Verbose diagnostics in `RoomStore.updateUnreadCount` under tag `RoomStoreUnreadDbg`. Logs the entry state (`activeJid`, `lastViewed`, `baseline`, `effectiveBaseline`, current-user identity fields) plus a per-message line showing exactly which filter dropped it (`delimiter` / `pending` / `sendFailed` / `deleted` / `system` / `ownMessage` / `ts<=baseline (diff=Nms)`). Filter at D level: `adb logcat -v time -s RoomStoreUnreadDbg:D RoomStore:D`. Critical when diagnosing host integrations whose unread badge appears stuck — tells you in one log line whether the message was classified as own, dropped by timestamp, or actually counted.
- **Tests:** SDK unit tests pass against the rebuilt artifact (`./gradlew :ethora-component:test`).

- **New:** `ChatService.lifecycle.onChatPaused(roomJid: String? = null)` / `onChatResumed(roomJid)` — host-facing API for signalling chat visibility when the SDK's auto-detection can't see the transition. Call `onChatPaused()` when the host navigates away from the chat surface (bottom-nav tab swap inside the same Activity, host-rendered overlay layered on top, etc.) and `onChatResumed()` when it comes back. Pause flushes the active room's read marker to the XMPP private store (XEP-0049 chatjson), bumps the local `lastViewedTimestamp` to now, and clears `currentRoom` so subsequent arrivals count toward the unread badge. Resume restores `currentRoom` and zeroes the local marker. The optional `roomJid` parameter lets badge-listener integrations (where the `Chat` composable isn't mounted and `RoomStore.currentRoom` may be null) target their listener room explicitly; falls back to the live `currentRoom` when omitted. Idempotent on repeated calls. Internal state reset on logout.
- **New:** `EthoraChatBootstrap` now registers its shared XMPPClient with `LogoutService.setXMPPClient(...)` as soon as `ensureConnected` returns true, so SDK services that need the live client (`ChatLifecycleService`, host-side callers reaching through `peekXMPPClient`) can find it before the `Chat` composable mounts. The later in-composable registration in `EthoraChat.kt` is a no-op re-register for the same instance and does not disconnect the live socket.
- **New:** `Chat` composable now auto-detects "actively viewing" without needing the new API for the common cases. Combines three Compose-native signals: `Modifier.onGloballyPositioned` for in-window bounds (catches 0-size `Box`, off-screen `HorizontalPager` page), `LocalWindowInfo.isWindowFocused` for Dialog/BottomSheet/IME-modal overlays, and `Lifecycle.State >= RESUMED` for app backgrounding. When all three are true the room is "active" (unread=0 shortcut on); any one false → server flush + drop the shortcut. Explicit `ChatService.lifecycle` only needed for tab swaps inside one Activity that keep `Chat` composed in another tab, or for same-window z-overlays.
- **Fixed:** `ChatRoomView.onDispose` write to the XMPP private store now reliably reaches the server. The previous implementation launched `writeCurrentTimestamp` on `rememberCoroutineScope()`, which is cancelled in the same Compose disposal tick — the suspending stanza send lost the race and the `set-chats-private-req` IQ never made it onto the socket. Read markers from tab-switch / room-close paths only updated locally and other devices saw stale state. New `InitBeforeLoadFlow.writeCurrentTimestampAsync(...)` runs the write on a long-lived SDK scope (`Dispatchers.IO + SupervisorJob`) that outlives the composable; all dispose / lifecycle / visibility paths now route through it.
- **Fixed:** Unread badge no longer "comes back" after a Disconnect → cold start. `EthoraApplication.onCreate` early bootstrap was gated only on `jwtToken.isNotBlank()`, so any cold start after Disconnect with the JWT still pasted in setup silently re-logged in, re-fetched `/chats/my`, and re-populated `RoomStore` — the badge reappeared even though the user had clicked Disconnect. Sample's early-bootstrap now also requires `session.isConnected == true`; tapping Disconnect persists `isConnected=false`, which now correctly defers bootstrap until the host taps Connect again.
- **Fixed:** Clicking Disconnect with a chat still open now writes the final read marker to the server before the XMPP socket is torn down. `LogoutService.performLogout` previously cleared stores and disconnected XMPP without ever calling the private-store write — so the "I just read everything, then logged out" case lost its read marker (other devices still saw old unread). New step 0a captures `RoomStore.currentRoom` and the live XMPP client *before* launching the cleanup coroutine and writes the current timestamp on the SDK long-lived scope; runs in parallel with store clearing so the badge drops immediately while the server write completes in the background. No-op when no room is active (e.g. user already navigated away to SETUP — the earlier `onDispose` flush already covered it).
- **Fixed:** Single-room badge listener integrations now reflect incoming messages while the host is on a non-chat surface. Previously, `EthoraChat.kt:230` called `setCurrentRoom(singleRoom)` once on mount and never cleared it, so `RoomStore.updateUnreadCount`'s active-room short-circuit forced `unread=0` for that room forever — even while the user was backgrounded or on another host tab. Visibility-driven detection + the new `ChatService.lifecycle` API now drop `currentRoom` whenever the user isn't actually viewing the chat, restoring real `msg.timestamp > effectiveBaseline` accounting.
- **Fixed:** Single-chat scroll-history pagination after bootstrap. `LaunchedEffect(listState)` captured the initial `ChatRoomViewModel` (the one wired to a `XMPPClientRegistry`-fallback client), so when bootstrap swapped in its own `EthoraChatBootstrap.sharedXmppClient` and Compose re-created the view-model, scroll-up MAM page requests kept going through the stale captured instance. Wrapped the view-model read with `rememberUpdatedState` so the running scroll-watcher coroutine always sees the latest view-model without restarting.
- **Improved:** `LogoutService.performLogout` restructured so the in-memory store clearing and the read-marker flush run **in parallel** instead of sequentially. The unread badge drops to zero on the same recomposition that flips `Chat ready: No`, rather than waiting for the up-to-12s read-then-write round trip plus the WebSocket disconnect. The flush still completes in the background and the server write is awaited before XMPP teardown.
- **Tests:** SDK unit tests pass against the rebuilt artifact (`./gradlew :ethora-component:test`).

---

## [26.05.14] — JitPack `v1.0.38`

- **Fixed:** `EthoraChatBootstrap.addUnreadListener` now reliably emits `hasUnread=true` after the bootstrap completes. The 8-second XMPP wait was returning `false` in ~2 ms because `XMPPClient.ensureConnected` early-exited on `status == OFFLINE`, but `initializeClient()` runs on a separate coroutine and hasn't transitioned the status yet when the wait starts. The bootstrap aborted before `InitBeforeLoadFlow.run`, the post-bootstrap baseline seed, the history preload, or the incremental catchup ever ran — host apps just saw the synchronous `hasUnread=false` initial callback and no further updates. `ensureConnected` now treats only `ERROR` as terminal and polls through `OFFLINE` (still bails immediately if a later transition lands in `ERROR`), so the launched connect coroutine has time to flip the status and the rest of the 8-stage bootstrap completes.
- **Fixed:** Cross-device read-marker sync against ejabberd `mod_private`. The chatjson parser only matched double-quoted XML attributes, but the server echoes the stored payload back with single quotes (`value='...'`), so `getChatsPrivateStore()` returned `null` on every real response and `InitBeforeLoadFlow` logged "Private store empty — nothing to merge". As a result, unread state never reconciled with what the user had already read on web/iOS. The regex now accepts both quote styles; extracted to a testable `parseChatjsonValue` helper.
- **Fixed:** Routed IQ responses now carry their raw XML to MAM-style collectors. `parseAndHandleIQ` built the `XMPPStanza` for collectors without the `xml` field, so any extension-element parser hooked through `mamCollectors` (chatjson, future XEPs) saw `stanza.xml = null` and bailed. Even with the regex above fixed, chatjson stayed empty until this was passed through.
- **Fixed:** `mergeSingleRoomPlaceholder` now preserves `unreadBaselineTimestamp` from the existing persisted room when an API-sourced room is merged over it (e.g. during a `/chats/my` refresh). Previously the baseline was silently dropped to `null`, which `v1.0.37`'s `lastViewedTimestamp` seed masked but did not fix — re-exposed whenever a room was open at process exit (`lastViewedTimestamp = 0`) and the next launch hit `effectiveBaseline = 0`. The merge now mirrors the existing `lastViewedTimestamp` preservation pattern.
- **Tests:** Added `ChatjsonParseTest` (single- and double-quoted attribute extraction, entity-escaped JSON, absent-element fallback) and a `SingleRoomSupportTest` case locking in `unreadBaselineTimestamp` preservation across merges.

---

## [26.05.14] — JitPack `v1.0.37`

- **Fixed:** `EthoraChatBootstrap.addUnreadListener` no longer stays pinned at `false` for rooms the server has no prior `chatjson` entry for (first install, or rooms never read from any device). Rooms returned by `/chats/my` flow through `createRoomFromApi` which hardcodes both `lastViewedTimestamp` and `unreadBaselineTimestamp` to `0`; the unread predicate in `RoomStore.updateUnreadCount` short-circuits to "not unread" whenever the effective baseline is `<= 0`, so `InitBeforeLoadFlow.run` finding nothing to merge left `hasUnread()` stuck at `false` for both the preload and live messages. After the chatjson merge, `runBootstrapLocked` now iterates `RoomStore.rooms.value` and seeds `lastViewedTimestamp = now` on any room the merge didn't cover — mirroring what `ChatPersistenceManager.loadRooms()` already does for the cache path. "Unread" thereafter means "arrived after bootstrap" for any room without a prior server read marker, matching the web SDK on a fresh install. No API changes; no behavioural change for rooms already covered by chatjson.
- **Tests:** Added `UnreadObserverTest` cases that drive the real pipeline (`createRoomFromApi` → seed → live `MessageStore.addMessage` → `hasUnread()` flips true), with the seed step duplicated inline so the regression intent is obvious.

---

## [26.05.12] — JitPack `v1.0.36`

- **Fixed:** MUC room joins now succeed against stricter ejabberd `mod_muc` configs that enforce `nickname == bare-jid-localpart`. `resolveOccupantNick()` no longer appends the device resource suffix (`-android-<id>`) to the user's xmppUsername when constructing the join presence — that was the Android outlier vs web/iOS and caused every `presenceInRoom` to come back `<forbidden><text>wrong nickname</text></forbidden>` on self-hosted deployments, leaving rooms "authenticated but never joined" with messages stuck `pending`. Multi-device disambiguation is unaffected (the full JID's resource still carries the device suffix).
- **Fixed:** Rapid-fire ("spam") sends now land in submission order and every stanza in a burst reaches the server. `MessageStore` reconciliation tracks server-assigned XEP-0359 `<stanza-id>` separately from the client-generated send id, so an edit issued immediately after a burst targets the right bubble instead of failing with `Message <id> not found in <jid> to edit`.
- **Fixed:** Editing a message after a back-to-back send burst replaces the original bubble in place — siblings stay intact and no stale "Sending failed" bubble lingers when the late server echo arrives. Edit/delete now resolves the correct message even when the user-issued id and the server archive id differ.
- **Fixed:** The "scroll to bottom" FAB unread badge stays consistent when re-entering a room: `lastViewedTimestamp` is zeroed and the unread counter recomputes once per room open, not on every recomposition.
- **Fixed:** Chat scroll-view no longer jumps under the input row when the IME opens during a long transcript; the active-message highlight no longer flickers when the FAB is tapped.
- **New:** `Message.archiveId: String?` — exposes the server-assigned XEP-0359 stanza-id captured from the realtime echo / MAM result. Populated by `parseAndHandleRealtimeMessage` and `parseMAMResult`; preserved through `MessageStore.reconcile` via `.copy()`. Defaults to `null`, so existing call sites compile unchanged.
- **New:** `Room.unreadBaselineTimestamp: Long?` — frozen baseline used by `RoomStore.computeUnreadForRoom` when the room has never been opened on this device but the server reported a cross-device "last read at" marker. Brings unread-counter parity with `@ethora/chat-component@26.3.20`. Defaults to `null`; safe additive change.
- **Improved:** `LogsView` shows richer diagnostic context (per-room stanza counts, last reconcile reason) when debugging unread / send / edit issues against a live server.
- **Tests:** Added `MessageSpamTest` (5-send burst ordering), `MessageStoreReconciliationTest` (echo-vs-client-id reconcile), `UnreadRecomputeTest` (baseline + view-side reset), and `XmppXmlUtilsTest` (XEP-0359 stanza-id extraction); expanded `MessageBubbleTest` for edit/delete affordances.

---

## [26.05.11] — JitPack `v1.0.35`

- **Build:** `:sample-chat-app` removed from the root `settings.gradle.kts`. This build is the SDK only (`chat-core` + `chat-ui` source sets collected under `:ethora-component`); the sample app is now a fully self-contained Gradle build under `sample-chat-app/` with its own wrapper. JitPack consumers see no change — the published artifact is still `com.github.dappros:ethora-sdk-android:vX.Y.Z`. Note: `v1.0.36` is a no-source-change retag of the same commit, published only to retrigger the JitPack build.

---

## [26.05.11] — JitPack `v1.0.34`

- **Fixed:** Room-history pagination is more resilient. `ChatRoomViewModel.loadMoreMessages(...)` now reconnects before requesting older MAM pages, promotes the room history cursor, and no longer treats a transient empty page as end-of-history until `Room.historyComplete == true`.
- **Fixed:** The "scroll to bottom" FAB unread counter no longer jumps when the user loads older history. `ChatRoomView` now distinguishes prepended history pages from genuinely new tail messages before incrementing `unreadCount`.
- **Fixed:** A late server echo or MAM catch-up now reconciles optimistic messages that had already timed out into `sendFailed = true`. Successful retry / delayed delivery clears the failed state instead of leaving a stale failed bubble in the transcript.
- **Fixed:** Pending-media queue persistence now keeps `caption` and `replyToMessageId`, so attachment+text sends survive process restart without losing the follow-up text or reply target.
- **Improved:** XMPP stanza parsing now covers `<body>` tags with attributes / namespaces and unwraps MUC-SUB pubsub envelopes before extracting the inner chat message, improving realtime and history parsing parity.
- **Tests:** Added regression coverage for pagination edge cases, optimistic-send reconciliation under late echoes, pending-media queue caption serialization, expanded XMPP XML parsing, and message-context-menu media cases.

---

## [26.05.06] — JitPack `v1.0.32`

- **New:** `EthoraChatSdk.shutdown()` — symmetric counterpart to `EthoraChatSdk.initialize(context)`. Disconnects the shared bootstrap XMPP client, resets the runtime sync flags, clears the cached fallback client, and flips the SDK's `initialized` flag so a subsequent `initialize(...)` re-runs the real setup. Persisted data — Room database, DataStore, encrypted token storage, pending-media files, scroll positions — is intentionally preserved so unsent messages survive a logout/login round-trip.
- **Docs:** Added an "SDK lifecycle" section to the README that spells out the three concentric scopes (`EthoraChatSdk` for persistence, `EthoraChatBootstrap` for session, `Chat` composable for UI) and which initialization sites are safe vs. which trigger the duplicate-DataStore failure. Also documents the logout/shutdown flow and clarifies that the `Chat` composable no longer disconnects the bootstrap-owned socket on dispose.
- **Build:** JitPack publication stays on the canonical coordinate `com.github.dappros:ethora-sdk-android:<version>`. The root POM aggregator is kept intentionally so JitPack discovers the build artifacts correctly; integrators should continue depending on the canonical coordinate rather than a module artifactId.
- **Docs:** README integrator dependency example updated to `com.github.dappros:ethora-sdk-android:<version>` — the coordinate JitPack actually publishes. The previous `com.github.dappros.ethora-sdk-android:ethora-component:<version>` form returned 404 because no module is published under that artifactId.

---

## [26.05.01] — JitPack `v1.0.31`

- **New:** `EthoraChatSdk.initialize(context)` — a one-shot, process-level initializer for SDK persistence, stores, message cache, pending media queue, message loader, scroll positions and push setup. It is idempotent so defensive repeated calls during host app startup are safe.
- **Fixed:** Recreating an Activity and re-running SDK setup no longer creates multiple active DataStore instances for `chat_persistence.preferences_pb` / `chat_storage.preferences_pb`. DataStore ownership is now singleton-backed and uses the application context.
- **Fixed:** Leaving the `Chat` composable no longer disconnects the shared bootstrap XMPP socket. Hosts using `EthoraChatBootstrap.initializeAsync(...)` with `addUnreadListener(...)` can keep receiving unread updates while the chat UI is unmounted; explicit logout / bootstrap shutdown still closes the socket.

---

## [26.04.30] — JitPack `v1.0.30`

- **Fixed:** Media bubbles no longer render as a blank surface when their image / preview URL fails to load (404, expired token, network down). `ImageMessage` and the preview thumbnail in `FileMessage` now track AsyncImage error state and fall back to a `InsertDriveFile` icon, so every media message has a visible icon regardless of CDN reachability.

---

## [26.04.30] — JitPack `v1.0.29`

- **New:** In-bubble timestamp + sent indicator on `MessageBubble`. For this iteration the double-check means "delivered to the server"; the same icon will be re-purposed once true delivery/read receipts are added.

## [26.04.28] — JitPack `v1.0.28`

- **Fixed:** File messages loaded from history (MAM) on login no longer render as plain-text bubbles. The MAM parser now extracts the `<data>` element when the archive serialises it as `<data ...></data>` (open/close form, in addition to the `<data .../>` self-closing form), so `isMediafile`, `location`, `mimetype`, `originalName`, `size`, `locationPreview` and `waveForm` survive the round-trip and `MessageBubble` renders the proper image / video / audio / file component instead of falling through to the text body.
- **Fixed:** Signed CDN URLs in history-loaded file messages no longer break on the `&` query separator. The MAM attribute parser now decodes `&amp;` (and the other four standard XML entities) the same way the live parser already did, so thumbnails load after a relogin.
- **Improved:** `XMPPClient` and `XMPPWebSocketConnection` share a single `XmppXmlUtils` helper for `<data>` extraction and attribute decoding, removing the duplicated-and-drifted logic that caused the two bugs above.

## [26.04.28] — JitPack `v1.0.27`

- **New:** `RetryConfig` on `ChatConfig` — `RetryConfig(autoRetry: Boolean = false, maxAttempts: Int = 3)`. **Default is `autoRetry = false`** — once a text or media send fails, the bubble stays in the "Sending failed. Tap to retry or delete." state until the user explicitly retries or deletes it. Pass `RetryConfig(autoRetry = true)` to restore the legacy silent-retry behaviour. Mid-session toggling lets the in-flight attempt finish, then stops scheduling further retries.
- **New:** `Message.sendFailed: Boolean?` field — explicit, persistent flag set when an optimistic send hits an XMPP-null return or the pending-timeout window without a server echo. Survives reconnects so the bubble does not silently flip back to "delivered".
- **New:** `EthoraChatBootstrap.hasUnread(): Flow<Boolean>` and `addUnreadListener(UnreadListener): AutoCloseable` — observe whether any room has unread messages from outside Compose (Activity, Service, Java callers). Boolean shape (rather than a count) matches the typical host UX — a tab dot or icon-state indicator that just needs "is there anything to see". `UnreadListener.onUnreadChanged(hasUnread: Boolean)` receives the current value synchronously on register, then on every change. Emits `false` before bootstrap completes; never throws. If you need the precise count, observe `RoomStore.rooms` and sum `unreadMessages`.
- **New:** `EthoraChatBootstrap.isInitialized: StateFlow<Boolean>` — observe whether the background bootstrap finished, for hosts that want to render before the chat tab opens.
- **New:** `FileSizeFormatter` (`chat-core/.../util/`) — single source of truth for byte-size rendering across chat input preview and bubble (1 KiB = 1024 B everywhere).
- **New:** Combo send — when the user has both an attachment and text staged in `ChatInput`, one Send tap dispatches **two messages** (media first, then text). Both appear as optimistic bubbles immediately and confirm/fail independently.
- **New:** Typing-indicator autoscroll — when another user starts typing while you're at-or-near the bottom, the list smoothly scrolls so the indicator is visible above the input. If you're scrolled up reading older messages, the scroll is left alone.
- **Improved:** Context menu position — anchors to the message bubble it belongs to. Prefers **above** the bubble (falls back to below if the bubble is near the top of the screen). Horizontal alignment follows the sender side. Menu height is now calculated from the actual visible items rather than an over-padded constant, so the menu sits flush against the bubble instead of floating far above.
- **Improved:** Failed messages get **Retry + Delete** in the context menu (Edit is hidden for any own message in `pending == true` or `sendFailed == true` state — editing a never-sent message would have no server effect). Manual user retry is always allowed, regardless of `RetryConfig.autoRetry`.
- **Improved:** Deleted bubble styling — renders in a neutral dimmed gray (`surfaceVariant @ 60% alpha`, no shadow) regardless of sender, replacing the full primary-coloured bubble that used to read as a normal active message.
- **Improved:** Deleting a never-sent message (`pending == true` OR `sendFailed == true`) removes the bubble locally without sending an XMPP delete — there's nothing on the server to delete.
- **Improved:** `EthoraChatBootstrap.initialize` validates `baseUrl` / `xmppSettings` immediately at call time. Missing required server config throws and emits `ChatConnectionStatus.ERROR` with a descriptive reason; no XMPP attempt is made.
- **Improved:** Test coverage added for `RetryConfig` wiring, `DnsFallback` host-only behaviour, `FileSizeFormatter`, `PendingMediaSendQueue` discipline, and `EthoraChatBootstrap.unreadCount` semantics.
- **Fixed:** Failed text sends no longer "magically recover" after the 6 s pending-timeout — the timeout now sets `sendFailed = true` instead of silently clearing `pending = false`.
- **Fixed:** Hardcoded fallback to a public Ethora dev server removed from `DnsFallback`. The SDK now only uses host-supplied `dnsFallbackOverrides`; missing entries throw `UnknownHostException`.
- **Fixed:** Sample app — stale `PendingTextSendQueue.initialize(...)` call removed from `MainActivity.initChatStores()` (the class was merged into `PendingMediaSendQueue` and is now initialised by the SDK itself inside `EthoraChat`/`EthoraChatProvider`).

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