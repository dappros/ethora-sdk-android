package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ethora.chat.core.store.RoomStore
import androidx.compose.runtime.collectAsState

data class UnreadState(
    val totalCount: Int,
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
            totalCount = totalCount,
            displayCount = displayCount
        )
    }
}
