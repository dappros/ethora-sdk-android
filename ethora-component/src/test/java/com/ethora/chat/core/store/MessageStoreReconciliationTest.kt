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

    /**
     * The "deleted message reappears" regression: user sends, then immediately
     * deletes while the row is still optimistic-pending. Old code wiped the
     * local row via `removeMessage`; the server echo for the original send
     * then had nothing to merge against, so `addMessage` added it as a brand-
     * new row and the deleted message reappeared in the UI.
     *
     * New contract (paired with `ChatRoomViewModel.deleteMessage`, which
     * now calls `markMessageAsDeleted` instead of `removeMessage` for
     * pending text):
     *   • Local row stays in the store with `isDeleted = true`.
     *   • The arriving echo merges in place but the deletion is sticky —
     *     `isDeleted` and the tombstone body remain.
     *   • In-flight flags (`pending`, `sendFailed`) clear to reflect that
     *     the original send did make it to the server.
     */
    @Test
    fun `single-path echo on a deleted-while-pending row keeps the tombstone`() {
        val now = System.currentTimeMillis()
        // Send -> optimistic pending.
        val optimistic = Message(
            id = "send-text-message:opt-A",
            user = User(id = "alice", xmppUsername = "alice@example.com"),
            date = Date(now),
            body = "hello",
            roomJid = roomJid,
            pending = true,
            timestamp = now,
            xmppId = "send-text-message:opt-A"
        )
        MessageStore.addMessage(roomJid, optimistic)
        // User deletes while still pending. ChatRoomViewModel calls this.
        MessageStore.markMessageAsDeleted(roomJid, "send-text-message:opt-A")

        // Server echo of the ORIGINAL send arrives a moment later.
        val echo = Message(
            id = "server-A",
            user = User(id = "alice@example.com", xmppUsername = "alice@example.com"),
            date = Date(now + 100L),
            body = "hello",
            roomJid = roomJid,
            pending = false,
            timestamp = now + 100L,
            xmppId = "send-text-message:opt-A"
        )
        MessageStore.addMessage(roomJid, echo)

        val rows = MessageStore.getMessagesForRoom(roomJid)
            .filterNot { it.id == "delimiter-new" }
        assertEquals(
            "deletion must NOT spawn a duplicate (id-match merge into the tombstone row)",
            1, rows.size
        )
        val row = rows.single()
        assertEquals(true, row.isDeleted)
        assertFalse("pending flag must clear once echo arrives", row.pending == true)
        assertNull("sendFailed must be null after echo", row.sendFailed)
        // Body must NOT be the original "hello" — the local tombstone string
        // is what the user expects to see (matches markMessageAsDeleted).
        assertEquals("This message was deleted.", row.body)
    }

    /**
     * Same contract over the bulk MAM path used by `schedulePendingFallback`
     * and `triggerFastAckFetch`. A delete on a pending row must survive the
     * 700 ms ack-catchup polls.
     */
    @Test
    fun `bulk MAM echo on a deleted-while-pending row keeps the tombstone`() {
        val now = System.currentTimeMillis()
        MessageStore.addMessage(
            roomJid,
            Message(
                id = "send-text-message:opt-B",
                user = User(id = "alice"),
                date = Date(now),
                body = "world",
                roomJid = roomJid,
                pending = true,
                timestamp = now,
                xmppId = "send-text-message:opt-B"
            )
        )
        MessageStore.markMessageAsDeleted(roomJid, "send-text-message:opt-B")

        // MAM batch returns the original message (server has it; doesn't
        // know about our delete yet because the delete stanza is still in
        // flight at this moment).
        MessageStore.addMessages(
            roomJid,
            listOf(
                Message(
                    id = "server-B",
                    user = User(id = "alice"),
                    date = Date(now + 200L),
                    body = "world",
                    roomJid = roomJid,
                    pending = false,
                    timestamp = now + 200L,
                    xmppId = "send-text-message:opt-B"
                )
            )
        )

        val rows = MessageStore.getMessagesForRoom(roomJid)
            .filterNot { it.id == "delimiter-new" }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(true, row.isDeleted)
        assertFalse(row.pending == true)
        assertNull(row.sendFailed)
        assertEquals("This message was deleted.", row.body)
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
