package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.ui.styling.TWO_PANE_LIST_WIDTH

/**
 * Wide-screen layout: the room list on the left, the selected chat on the right.
 * Selection state is owned by the caller, so switching between this and the
 * single-pane layout keeps the open room.
 */
@Composable
fun TwoPaneRoomLayout(
    selectedRoom: Room?,
    xmppClient: XMPPClient?,
    onRoomSelected: (Room) -> Unit,
    onRoomClosed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        RoomListView(
            onRoomSelected = onRoomSelected,
            modifier = Modifier.width(TWO_PANE_LIST_WIDTH).fillMaxHeight()
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (selectedRoom != null) {
                ChatRoomView(
                    room = selectedRoom,
                    xmppClient = xmppClient,
                    onBack = onRoomClosed,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Select a chat to start messaging",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}
