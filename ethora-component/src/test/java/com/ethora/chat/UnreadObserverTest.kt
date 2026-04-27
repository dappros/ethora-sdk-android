package com.ethora.chat

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.store.RoomStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadObserverTest {
    @Test
    fun `bootstrap unreadCount exposes RoomStore unread count outside Compose`() = runBlocking {
        RoomStore.clear()
        RoomStore.setRooms(
            listOf(
                Room(id = "1", jid = "one@example.com", name = "One", title = "One", unreadMessages = 2),
                Room(id = "2", jid = "two@example.com", name = "Two", title = "Two", unreadMessages = 3),
                Room(id = "3", jid = "three@example.com", name = "Three", title = "Three", unreadMessages = -1)
            )
        )

        assertEquals(5, EthoraChatBootstrap.unreadCount().first())
    }

    @Test
    fun `Java listener receives current unread count immediately`() {
        RoomStore.clear()
        RoomStore.setRooms(listOf(Room(id = "1", jid = "one@example.com", name = "One", title = "One", unreadMessages = 7)))
        var observed = -1

        val registration = EthoraChatBootstrap.addUnreadListener { count -> observed = count }

        registration.close()
        assertEquals(7, observed)
    }
}
