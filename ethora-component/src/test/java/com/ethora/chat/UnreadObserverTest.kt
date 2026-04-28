package com.ethora.chat

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.store.RoomStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadObserverTest {
    @Test
    fun `bootstrap hasUnread emits true when any room has unread messages`() = runBlocking {
        RoomStore.clear()
        RoomStore.setRooms(
            listOf(
                Room(id = "1", jid = "one@example.com", name = "One", title = "One", unreadMessages = 2),
                Room(id = "2", jid = "two@example.com", name = "Two", title = "Two", unreadMessages = 3),
                Room(id = "3", jid = "three@example.com", name = "Three", title = "Three", unreadMessages = -1)
            )
        )

        assertTrue(EthoraChatBootstrap.hasUnread().first())
    }

    @Test
    fun `bootstrap hasUnread emits false when all rooms are zero`() = runBlocking {
        RoomStore.clear()
        RoomStore.setRooms(
            listOf(
                Room(id = "1", jid = "one@example.com", name = "One", title = "One", unreadMessages = 0),
                Room(id = "2", jid = "two@example.com", name = "Two", title = "Two", unreadMessages = 0)
            )
        )

        assertFalse(EthoraChatBootstrap.hasUnread().first())
    }

    @Test
    fun `Java listener receives current has-unread immediately`() {
        RoomStore.clear()
        RoomStore.setRooms(listOf(Room(id = "1", jid = "one@example.com", name = "One", title = "One", unreadMessages = 7)))
        var observed = false

        val registration = EthoraChatBootstrap.addUnreadListener { hasUnread -> observed = hasUnread }

        registration.close()
        assertTrue(observed)
    }
}
