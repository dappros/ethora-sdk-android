package com.ethora.chat.core.service

import android.util.Log
import com.ethora.chat.core.store.InitBeforeLoadFlow
import com.ethora.chat.core.store.RoomStore

/**
 * Host-app-facing API for signalling chat visibility changes that Compose
 * cannot detect on its own.
 *
 * The SDK already auto-detects backgrounding (Android lifecycle), zero-size
 * containers (`Modifier.onGloballyPositioned`), and Dialog/BottomSheet overlays
 * (`WindowInfo.isWindowFocused`). What it cannot detect:
 *
 *   • Host app swaps tabs inside the same Activity while keeping the `Chat`
 *     composable mounted in another tab as a badge listener.
 *   • Host app overlays `Chat` with another composable in the same window
 *     (Compose has no z-order awareness for visibility purposes).
 *
 * For those, the host calls [onChatPaused] when navigating away and
 * [onChatResumed] when navigating back. Each call:
 *
 *   • Writes the current `lastViewedTimestamp` to the XMPP private store
 *     (XEP-0049 chatjson). Server-side state stays consistent across devices.
 *   • Flips the local `currentRoom` so [RoomStore.updateUnreadCount] either
 *     short-circuits to 0 (resumed) or actually counts arrivals (paused).
 *
 * Idempotent: calling pause twice in a row is a no-op the second time.
 *
 * Usage:
 * ```kotlin
 * // Host app's bottom-nav callback:
 * override fun onTabSelected(tab: Tab) {
 *     if (tab == Tab.CHAT) ChatService.lifecycle.onChatResumed()
 *     else                  ChatService.lifecycle.onChatPaused()
 * }
 * ```
 */
object ChatLifecycleService {
    private const val TAG = "ChatLifecycleService"

    @Volatile private var lastActiveRoomJid: String? = null
    @Volatile private var isPaused: Boolean = false

    /**
     * Signal that the user is no longer viewing the chat. Writes the read
     * marker to the XMPP private store and stops treating the room as
     * "active" so subsequent messages count toward the unread badge.
     *
     * @param roomJid optional explicit room JID. When null, falls back to
     * [RoomStore.currentRoom]. Pass it explicitly when your integration
     * keeps the `Chat` composable in some hidden state (badge listener in
     * a 0-size container, etc.) where `currentRoom` may already be null —
     * you still know which room your listener is watching.
     */
    @JvmOverloads
    fun onChatPaused(roomJid: String? = null) {
        if (isPaused) return
        val resolvedJid = roomJid ?: RoomStore.currentRoom.value?.jid
        if (resolvedJid.isNullOrBlank()) {
            Log.d(TAG, "onChatPaused: no active room and no jid passed — skipping")
            return
        }
        val room = RoomStore.getRoomByJid(resolvedJid)
        if (room == null) {
            Log.w(TAG, "onChatPaused: room $resolvedJid not in RoomStore — skipping")
            return
        }
        // v1.0.40: was `System.currentTimeMillis()` — now resolves to the
        // latest message the SDK actually knows about for this room (or
        // keeps the existing marker if there's nothing newer). See
        // `RoomStore.resolveReadMarkerOnLeave` for the host-side unread-
        // preview rationale.
        val ts = RoomStore.resolveReadMarkerOnLeave(room.jid)
        lastActiveRoomJid = room.jid
        isPaused = true
        RoomStore.setLastViewedTimestamp(room.jid, ts)
        RoomStore.setCurrentRoom(null)
        // Safety net: re-run `updateUnreadCount` for every room from the
        // current `MessageStore` state. Closes the failure mode where a
        // message arrived during the visibility transition (e.g. while
        // `currentRoom` was still set and the active-room shortcut forced
        // unread=0) and never got re-evaluated after we cleared the room.
        // Also catches host listeners that are sitting on a stale
        // `hasUnread()` boolean because no `RoomStore.rooms` emission
        // happened to nudge them. Cheap and idempotent — same code path
        // incoming messages take, so the answer is identical to what would
        // arrive on the next real message.
        RoomStore.recomputeUnreadForAllRooms()
        InitBeforeLoadFlow.writeCurrentTimestampAsync(
            xmppClient = LogoutService.peekXMPPClient(),
            roomJid = room.jid,
            timestamp = ts
        )
        Log.d(TAG, "onChatPaused: flushed room=${room.jid} ts=$ts")
    }

    /**
     * Inverse of [onChatPaused]. Restores the room as `currentRoom` so the
     * active-room shortcut zeros unread again, and resets its local
     * `lastViewedTimestamp` to 0 (user is looking now — the marker is
     * meaningless until they leave). No server write needed.
     *
     * @param roomJid optional explicit room JID. When null, falls back to
     * the JID remembered from the previous [onChatPaused] call.
     */
    @JvmOverloads
    fun onChatResumed(roomJid: String? = null) {
        val resolvedJid = roomJid ?: lastActiveRoomJid
        if (resolvedJid.isNullOrBlank()) {
            Log.d(TAG, "onChatResumed: no jid to restore — skipping")
            isPaused = false
            return
        }
        val room = RoomStore.getRoomByJid(resolvedJid)
        if (room == null) {
            Log.w(TAG, "onChatResumed: room $resolvedJid no longer in RoomStore")
            isPaused = false
            return
        }
        RoomStore.setLastViewedTimestamp(room.jid, 0)
        RoomStore.setCurrentRoom(room)
        // Mirrors the pause-side safety net (see [onChatPaused]). When the
        // host re-shows chat, other rooms in the same store may have
        // accumulated unread that a stuck listener didn't pick up — force a
        // recompute so the `hasUnread()` boolean reflects current truth.
        // The active room is unaffected here (active-room shortcut zeroes
        // it immediately), so the recompute only matters for multi-room
        // hosts; harmless in single-room.
        RoomStore.recomputeUnreadForAllRooms()
        isPaused = false
        Log.d(TAG, "onChatResumed: restored room=${room.jid}")
    }

    /** Reset internal state — called from [LogoutService] on logout. */
    internal fun reset() {
        lastActiveRoomJid = null
        isPaused = false
    }
}
