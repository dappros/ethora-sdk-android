package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Date

class MessageStoreReconciliationTest {
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
    }

    @Test
    fun `late server echo replaces failed optimistic message instead of adding duplicate`() {
        val now = System.currentTimeMillis()
        val optimisticUser = User(id = "alice", xmppUsername = "alice@example.com")
        val optimistic = Message(
            id = "send-text-message:local-1",
            user = optimisticUser,
            date = Date(now),
            body = "hello",
            roomJid = roomJid,
            pending = false,
            sendFailed = true,
            timestamp = now,
            xmppId = "send-text-message:local-1"
        )
        MessageStore.addMessage(roomJid, optimistic)

        val echoed = Message(
            id = "server-1",
            user = User(id = "alice@example.com", xmppUsername = "alice@example.com"),
            date = Date(now + 200L),
            body = "hello",
            roomJid = roomJid,
            pending = false,
            timestamp = now + 200L,
            xmppId = "server-1"
        )

        MessageStore.addMessages(roomJid, listOf(echoed))

        val messages = MessageStore.getMessagesForRoom(roomJid)
            .filterNot { it.id == "delimiter-new" }
        assertEquals(1, messages.size)
        assertEquals("send-text-message:local-1", messages.single().id)
        assertFalse(messages.single().pending == true)
        assertNull(messages.single().sendFailed)
    }
}
