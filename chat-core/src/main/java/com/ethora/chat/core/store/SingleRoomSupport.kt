package com.ethora.chat.core.store

import com.ethora.chat.core.models.HistoryPreloadState
import com.ethora.chat.core.models.Room

fun normalizeRoomJid(inputJid: String, conferenceDomain: String?): String {
    if (inputJid.contains("@")) return inputJid
    if (conferenceDomain.isNullOrBlank()) return inputJid
    return "$inputJid@$conferenceDomain"
}

fun toBareRoomName(jid: String): String = jid.substringBefore("@")

fun findRoomByJid(rooms: List<Room>, targetJid: String): Room? {
    return rooms.firstOrNull { room ->
        room.jid == targetJid || toBareRoomName(room.jid) == toBareRoomName(targetJid)
    }
}

fun buildPlaceholderRoom(
    normalizedRoomJid: String,
    originalRoomJid: String?,
    titleOverride: String?
): Room {
    val bare = toBareRoomName(normalizedRoomJid)
    val resolvedTitle = titleOverride?.takeIf { it.isNotBlank() } ?: originalRoomJid ?: bare
    return Room(
        id = originalRoomJid?.ifBlank { bare } ?: bare,
        jid = normalizedRoomJid,
        name = resolvedTitle,
        title = resolvedTitle,
        historyPreloadState = HistoryPreloadState.IDLE,
        isPlaceholder = true,
        presenceReady = false
    )
}

/**
 * Merge an API-sourced / placeholder [room] over [existing]. Keeps local
 * unread/pending sticky so /chats/my refresh doesn't wipe them. Only safe
 * for API-sourced writes — `RoomStore.updateRoom` bypasses this.
 */
fun mergeSingleRoomPlaceholder(room: Room, existing: Room?): Room {
    if (existing == null) return room
    return room.copy(
        role = room.role ?: existing.role,
        lastViewedTimestamp = room.lastViewedTimestamp ?: existing.lastViewedTimestamp,
        unreadMessages = room.unreadMessages.takeIf { it > 0 } ?: existing.unreadMessages,
        pendingMessages = room.pendingMessages.takeIf { it > 0 } ?: existing.pendingMessages,
        messageStats = room.messageStats ?: existing.messageStats,
        historyComplete = room.historyComplete ?: existing.historyComplete,
        historyPreloadState = if (room.historyPreloadState != HistoryPreloadState.IDLE) {
            room.historyPreloadState
        } else {
            existing.historyPreloadState
        },
        isPlaceholder = false,
        presenceReady = room.presenceReady || existing.presenceReady
    )
}
