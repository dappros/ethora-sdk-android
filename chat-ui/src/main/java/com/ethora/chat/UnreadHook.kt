package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ethora.chat.core.store.RoomStore
import androidx.compose.runtime.collectAsState
import com.ethora.chat.core.models.Room

data class UnreadState(
    /** True when ANY room has unread messages. Use for dot-style indicators. */
    val hasUnread: Boolean,
    /** Total unread across rooms. Kept for backward compat / diagnostics —
     *  prefer `hasUnread` when you only need a boolean indicator. */
    val totalCount: Int,
    /** Pretty-printed total (`0`, `3`, `10+`). */
    val displayCount: String
)

object UnreadCounter {
    fun total(rooms: List<Room>): Int = rooms.sumOf { room ->
        room.unreadMessages.coerceAtLeast(0)
    }

    fun state(rooms: List<Room>, maxCount: Int): UnreadState {
        val totalCount = total(rooms)
        val displayCount = if (totalCount > maxCount) "$maxCount+" else totalCount.toString()
        return UnreadState(
            hasUnread = totalCount > 0,
            totalCount = totalCount,
            displayCount = displayCount
        )
    }
}

@Composable
fun useUnread(maxCount: Int = 10): UnreadState {
    val rooms by RoomStore.rooms.collectAsState(initial = emptyList())

    return remember(rooms, maxCount) {
        UnreadCounter.state(rooms, maxCount)
    }
}
