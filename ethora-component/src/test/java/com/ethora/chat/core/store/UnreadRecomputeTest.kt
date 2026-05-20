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
        // Plant a read baseline before the message lands — web's AT()
        // returns 0 unread when there is no baseline at all, so we need a
        // real one for the own-message exclusion to be the deciding factor.
        RoomStore.setLastViewedTimestamp(roomJid, 500L)
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
    fun `unread is zero when both lastViewed and baseline are zero (web parity)`() {
        // Web `AT()` contract: `const a = r > 0 ? r : s; ... return l <= 0 || a <= 0 ? false : l > a`.
        // No marker anywhere → 0 unread. The previous Android logic
        // counted EVERY message as unread in this state, which made fresh
        // rooms show wrong badge counts until the user opened them.
        UserStore.setUser(User(id = "user-123", xmppUsername = "alice@example.com"))
        MessageStore.addMessages(
            roomJid,
            listOf(
                Message(
                    id = "from-peer-1",
                    user = User(id = "bob@example.com", xmppUsername = "bob@example.com"),
                    date = Date(1_000L),
                    body = "incoming",
                    roomJid = roomJid,
                    timestamp = 1_000L
                )
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
        // Plant a baseline so the only thing that can make the unread
        // counter drop to 0 here is the own-message exclusion (otherwise
        // we'd be measuring the "baseline=0 → 0 unread" branch instead).
        RoomStore.setLastViewedTimestamp(roomJid, 500L)

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
        RoomStore.setLastViewedTimestamp(roomJid, 500L)

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
    fun `MUC xmppFrom room prefix is not misclassified as own sender`() {
        // Regression for the field report: in Ethora MUC rooms the stanza
        // `from` is `roomJid/senderResource`, and the room's local part is
        // `<creatorId>_<uuid>`. The previous `identityCandidates(xmppFrom)`
        // call yielded the bare room JID and its local part as sender
        // candidates, so a current user whose `id` / `xmppUsername` happened
        // to overlap with the room's local part would falsely classify every
        // incoming MUC message as "own" — and `addUnreadListener` would stay
        // pinned at `false` forever. We strip the room half of `xmppFrom`
        // before identity matching so only the sender resource contributes.
        UserStore.setUser(
            User(
                // Worst-case collision: the host configures the user record
                // such that `id` exactly equals the room's local part (the
                // failure surface we're protecting against — the field bug
                // looked like this whenever the host accidentally seeded
                // the user from the room creator record).
                id = "creator_uuid",
                xmppUsername = "alice@example.com"
            )
        )
        RoomStore.setLastViewedTimestamp(roomJid, 1_000L)
        val collidingRoomJid = "creator_uuid@conference.example.com"
        RoomStore.setRooms(
            listOf(
                Room(
                    id = "room-2",
                    jid = collidingRoomJid,
                    name = "muc",
                    title = "MUC"
                )
            )
        )
        RoomStore.setLastViewedTimestamp(collidingRoomJid, 1_000L)

        MessageStore.addMessages(
            collidingRoomJid,
            listOf(
                Message(
                    id = "muc-incoming-1",
                    // Real sender — distinct from current user.
                    user = User(id = "bob", xmppUsername = "bob@example.com"),
                    date = Date(2_000L),
                    body = "hi from MUC",
                    roomJid = collidingRoomJid,
                    timestamp = 2_000L,
                    // Stanza `from` follows the MUC convention; the room
                    // half collides with the current user's id.
                    xmppFrom = "$collidingRoomJid/bob"
                )
            )
        )

        assertEquals(
            "MUC incoming must not be eaten by room/user-id collision",
            1,
            RoomStore.getRoomByJid(collidingRoomJid)?.unreadMessages
        )
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
