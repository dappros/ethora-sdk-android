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

    @Test
    fun `own optimistic row carrying Ethora user id does not count as unread`() {
        // Bug 3 scenario: ChatRoomViewModel.sendMessage creates an optimistic
        // row with `user = currentUser`, which means `user.id` is the Ethora
        // user id (e.g. "user-123") — NOT the XMPP local part. The previous
        // single-field check (`msg.user.id.substringBefore("@")` vs
        // `xmppUsername.substringBefore("@")`) would compare "user-123" to
        // "alice" and decide the message wasn't own, so the message landed
        // in the unread counter.
        UserStore.setUser(
            User(
                id = "user-123",
                xmppUsername = "alice@example.com"
            )
        )

        MessageStore.addMessages(
            roomJid,
            listOf(
                Message(
                    id = "send-text-message:opt-1",
                    user = User(
                        // Mirrors the optimistic path: id = current Ethora id,
                        // not the XMPP local part.
                        id = "user-123",
                        xmppUsername = "alice@example.com"
                    ),
                    date = Date(2_000L),
                    body = "outgoing optimistic body",
                    roomJid = roomJid,
                    timestamp = 2_000L,
                    pending = false,
                    sendFailed = null
                )
            )
        )

        assertEquals(
            "own message must not count as unread regardless of which identity field carries it",
            0,
            RoomStore.getRoomByJid(roomJid)?.unreadMessages
        )
    }

    @Test
    fun `own send-failed message does not count as unread`() {
        // Bug 3 explicit clause: "Failed/sending/retried outgoing messages
        // must never increase unread count." Before the fix `sendFailed` rows
        // were not filtered by `updateUnreadCount` at all — the only filter
        // was on `pending` — and own-failed rows leak through because their
        // `user.id` is the Ethora id (see above), failing the simple match.
        UserStore.setUser(
            User(
                id = "user-123",
                xmppUsername = "alice@example.com"
            )
        )

        MessageStore.addMessages(
            roomJid,
            listOf(
                Message(
                    id = "send-text-message:failed-1",
                    user = User(id = "user-123", xmppUsername = "alice@example.com"),
                    date = Date(3_000L),
                    body = "stuck send",
                    roomJid = roomJid,
                    timestamp = 3_000L,
                    pending = false,
                    sendFailed = true
                )
            )
        )

        assertEquals(0, RoomStore.getRoomByJid(roomJid)?.unreadMessages)
    }

    @Test
    fun `incoming message from another user past lastViewed still counts`() {
        // Guard rail: the new isOwnMessage-based filter must NOT swallow
        // legitimate incoming messages. Set current user; arrival from
        // someone else with timestamp > lastViewedTimestamp must bump the
        // counter.
        UserStore.setUser(
            User(
                id = "user-123",
                xmppUsername = "alice@example.com"
            )
        )
        // Mark the room "viewed up to t=1000".
        RoomStore.setLastViewedTimestamp(roomJid, 1_000L)

        MessageStore.addMessages(
            roomJid,
            listOf(
                Message(
                    id = "incoming-1",
                    user = User(id = "bob", xmppUsername = "bob@example.com"),
                    date = Date(2_000L),
                    body = "hey",
                    roomJid = roomJid,
                    timestamp = 2_000L
                )
            )
        )

        assertEquals(1, RoomStore.getRoomByJid(roomJid)?.unreadMessages)
    }
}
