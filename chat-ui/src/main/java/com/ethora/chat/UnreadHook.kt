package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ethora.chat.core.store.RoomStore
import androidx.compose.runtime.collectAsState

data class UnreadState(
    /** True when ANY room has unread messages. Use for dot-style indicators. */
    val hasUnread: Boolean,
    /** Total unread across rooms. Kept for backward compat / diagnostics —
     *  prefer `hasUnread` when you only need a boolean indicator. */
    val totalCount: Int,
    /** Pretty-printed total (`0`, `3`, `10+`). */
    val displayCount: String
)

@Composable
fun useUnread(maxCount: Int = 10): UnreadState {
    val rooms by RoomStore.rooms.collectAsState(initial = emptyList())

    return remember(rooms, maxCount) {
        val totalCount = rooms.sumOf { room ->
            room.unreadMessages.coerceAtLeast(0)
        }
        val displayCount = if (totalCount > maxCount) "$maxCount+" else totalCount.toString()
        UnreadState(
            hasUnread = totalCount > 0,
            totalCount = totalCount,
            displayCount = displayCount
        )
    }
}
