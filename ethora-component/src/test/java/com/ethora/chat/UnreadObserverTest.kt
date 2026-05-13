package com.ethora.chat

import com.ethora.chat.core.models.ApiRoom
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.User
import com.ethora.chat.core.models.createRoomFromApi
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

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

    /**
     * Regression for the customer-reported bootstrap-only unread bug:
     * rooms returned by `/chats/my` get `lastViewedTimestamp = 0` and
     * `unreadBaselineTimestamp = 0` from `createRoomFromApi`. The unread
     * predicate in `RoomStore.updateUnreadCount` short-circuits to false
     * when the effective baseline is `<= 0`, so without an explicit seed
     * `hasUnread()` stays pinned at false for every incoming message —
     * exactly the field report ("listener never receives any updates").
     *
     * Bootstrap step 6b in `EthoraChatBootstrap` seeds
     * `lastViewedTimestamp = now` for any room that didn't pick up a
     * server-side chatjson value, mirroring what
     * `ChatPersistenceManager.loadRooms()` already does for the cache
     * path. This test exercises the real pipeline:
     * `setRooms(createRoomFromApi)` → seed step → `MessageStore.addMessage`
     * → `EthoraChatBootstrap.hasUnread()` flips true.
     */
    @Test
    fun `bootstrap seed lets live incoming messages bump hasUnread for rooms loaded from chats my`() = runBlocking {
        MessageStore.clear()
        RoomStore.clear()
        UserStore.clear()
        UserStore.setUser(User(id = "me", xmppUsername = "alice@example.com"))

        // Simulate /chats/my landing — same path as RoomsAPIHelper.getRooms()
        // → createRoomFromApi → setRooms. Both baselines hardcoded to 0
        // by createRoomFromApi.
        val apiRoom = ApiRoom(
            name = "room1",
            type = RoomType.GROUP,
            title = "Room 1",
            _id = "room-1"
        )
        val conferenceDomain = "conference.example.com"
        val roomJid = "room1@$conferenceDomain"
        RoomStore.setRooms(listOf(createRoomFromApi(apiRoom, conferenceDomain)))

        // Replicate bootstrap step 6b — seed lastViewedTimestamp for any
        // room the chatjson merge didn't cover. In production this runs
        // inside EthoraChatBootstrap.runBootstrapLocked right after
        // InitBeforeLoadFlow.run(client). Drop these three lines to
        // reproduce the bug.
        val baseline = System.currentTimeMillis()
        RoomStore.rooms.value.forEach { room ->
            if ((room.lastViewedTimestamp ?: 0L) <= 0L) {
                RoomStore.setLastViewedTimestamp(room.jid, baseline)
            }
        }

        // Live incoming message arriving via XMPPWebSocket →
        // MessageStore.addMessage. Without the seed above, baseline is 0
        // and updateUnreadCount filters this message out → hasUnread()
        // stays false even though a peer message just arrived.
        MessageStore.addMessage(
            roomJid,
            Message(
                id = "msg-1",
                user = User(id = "bob", xmppUsername = "bob@example.com"),
                date = Date(baseline + 5_000L),
                body = "hello",
                roomJid = roomJid,
                timestamp = baseline + 5_000L
            )
        )

        assertEquals(
            "Live message after bootstrap seed must increment unreadMessages",
            1,
            RoomStore.getRoomByJid(roomJid)?.unreadMessages
        )
        assertTrue(
            "EthoraChatBootstrap.hasUnread() must flip true once any room has unread > 0",
            EthoraChatBootstrap.hasUnread().first()
        )
    }
}
