package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.User
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

class UnreadRecomputeTest {
    private val roomJid = "room@conference.example.com"

    @Before
    fun setUp() {
        MessageStore.clear()
        RoomStore.clear()
        UserStore.clear()
        RoomStore.setRooms(
            listOf(
                Room(
                    id = "room-1",
                    jid = roomJid,
                    name = "room",
                    title = "Room"
                )
            )
        )
        RoomStore.setCurrentRoom(null)
    }

    @Test
    fun `restoring current user recomputes unread counts for own messages`() {
        MessageStore.addMessages(
            roomJid,
            listOf(
                Message(
                    id = "message-1",
                    user = User(id = "alice@example.com", xmppUsername = "alice@example.com"),
                    date = Date(1_000L),
                    body = "my own message",
                    roomJid = roomJid,
                    timestamp = 1_000L
                )
            )
        )

        assertEquals(1, RoomStore.getRoomByJid(roomJid)?.unreadMessages)

        UserStore.setUser(
            User(
                id = "user-123",
                xmppUsername = "alice@example.com"
            )
        )

        assertEquals(0, RoomStore.getRoomByJid(roomJid)?.unreadMessages)
    }
}
