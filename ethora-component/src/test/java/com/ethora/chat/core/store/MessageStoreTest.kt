package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Date

/**
 * Unit tests for the [MessageStore] singleton.
 *
 * Covers the load-bearing behaviour around `addMessage`'s bidirectional
 * ID matching for pending messages — this is the path that flips a
 * just-sent bubble from the optimistic "sending…" state into a confirmed
 * server-echoed message without double-rendering it as two bubbles.
 *
 * Each test calls `MessageStore.clear()` + `RoomStore.clear()` in
 * `@Before` / `@After` because the SUT touches RoomStore for last-
 * message and pending-count side effects.
 *
 * `MessageStore.persistMessage()` requires a persistence manager and
 * returns early without one — like RoomStore, the SUT is safe to drive
 * in a hermetic JVM environment without initialising DataStore.
 */
class MessageStoreTest {

    @Before
    fun resetStores() {
        MessageStore.clear()
        RoomStore.clear()
    }

    @After
    fun tearDownStores() {
        MessageStore.clear()
        RoomStore.clear()
    }

    // --- helpers -------------------------------------------------------

    private fun makeMessage(
        id: String,
        body: String = "hello",
        roomJid: String = "room-1@conference.xmpp.example.com",
        pending: Boolean? = null,
        xmppId: String? = null,
        userId: String = "u-1",
    ) = Message(
        id = id,
        user = User(id = userId, firstName = "Alice"),
        date = Date(),
        body = body,
        roomJid = roomJid,
        pending = pending,
        xmppId = xmppId,
    )

    // --- getMessagesForRoom / setMessagesForRoom -----------------------

    @Test
    fun `getMessagesForRoom returns empty list for unknown room`() {
        val msgs = MessageStore.getMessagesForRoom("ghost@conference.xmpp.example.com")
        assertEquals(0, msgs.size)
    }

    @Test
    fun `setMessagesForRoom seeds messages and getMessagesForRoom reads them back`() {
        val room = "room-1@conference.xmpp.example.com"
        val seeded = listOf(
            makeMessage("m1", body = "hi", roomJid = room),
            makeMessage("m2", body = "there", roomJid = room),
        )
        MessageStore.setMessagesForRoom(room, seeded)

        val read = MessageStore.getMessagesForRoom(room)
        // Order matters: setMessagesForRoom applies sortedForUi + delimiter
        // normalisation. For two fresh non-delimiter messages with the
        // same Date the order is preserved deterministically.
        assertEquals(2, read.size)
        val bodies = read.map { it.body }.toSet()
        assertEquals(setOf("hi", "there"), bodies)
    }

    @Test
    fun `setMessagesForRoom for one room leaves other rooms untouched`() {
        val roomA = "room-a@conference.xmpp.example.com"
        val roomB = "room-b@conference.xmpp.example.com"
        MessageStore.setMessagesForRoom(roomA, listOf(makeMessage("a1", roomJid = roomA)))
        MessageStore.setMessagesForRoom(roomB, listOf(makeMessage("b1", roomJid = roomB)))

        // Re-seed roomA — roomB should remain.
        MessageStore.setMessagesForRoom(roomA, listOf(makeMessage("a2", roomJid = roomA)))

        assertEquals(1, MessageStore.getMessagesForRoom(roomB).size)
        assertEquals("b1", MessageStore.getMessagesForRoom(roomB)[0].id)
        assertEquals("a2", MessageStore.getMessagesForRoom(roomA)[0].id)
    }

    // --- addMessage: new-message append --------------------------------

    @Test
    fun `addMessage appends to empty room and returns false (no pending match)`() {
        val room = "room-1@conference.xmpp.example.com"
        val matched = MessageStore.addMessage(room, makeMessage("m1"))

        assertFalse("returns false when nothing was pending to match", matched)
        assertEquals(1, MessageStore.getMessagesForRoom(room).size)
    }

    @Test
    fun `addMessage with a non-matching id appends a second bubble`() {
        val room = "room-1@conference.xmpp.example.com"
        MessageStore.addMessage(room, makeMessage("m1"))
        MessageStore.addMessage(room, makeMessage("m2"))
        assertEquals(2, MessageStore.getMessagesForRoom(room).size)
    }

    // --- addMessage: pending-message merge -----------------------------

    @Test
    fun `addMessage with matching id on a pending message clears pending and returns true`() {
        // Simulate the canonical send flow: optimistic bubble pushed
        // with pending=true, server echo arrives with same id.
        val room = "room-1@conference.xmpp.example.com"
        MessageStore.addMessage(room, makeMessage("m-pending", pending = true))

        val matched = MessageStore.addMessage(room, makeMessage("m-pending", pending = false))
        assertTrue("server echo of a pending message must report matched=true", matched)

        val after = MessageStore.getMessagesForRoom(room)
        assertEquals("merge must not duplicate the bubble", 1, after.size)
        assertNotEquals(
            "pending must clear after the server echo lands",
            true, after[0].pending
        )
    }

    @Test
    fun `addMessage non-pending duplicate is dropped and returns false`() {
        // Idempotency: MAM replay can deliver the same id twice. The
        // second add must not append a second bubble.
        val room = "room-1@conference.xmpp.example.com"
        MessageStore.addMessage(room, makeMessage("m1", pending = false))
        val matched = MessageStore.addMessage(room, makeMessage("m1", pending = false))

        assertFalse(matched)
        assertEquals(1, MessageStore.getMessagesForRoom(room).size)
    }

    @Test
    fun `addMessage merges across optimistic id vs server xmppId`() {
        // The send path emits id=<optimistic-uuid>, server echo comes
        // back with id=<server-id>, xmppId=<optimistic-uuid>. The
        // bidirectional ID matching must reconcile them as one bubble.
        val room = "room-1@conference.xmpp.example.com"
        val optimisticId = "optimistic-uuid-123"
        val serverId = "server-id-456"

        MessageStore.addMessage(room, makeMessage(optimisticId, pending = true))
        val matched = MessageStore.addMessage(
            room,
            makeMessage(serverId, pending = false, xmppId = optimisticId)
        )

        assertTrue(
            "server echo carrying xmppId=optimistic must match the local pending bubble",
            matched
        )
        assertEquals(1, MessageStore.getMessagesForRoom(room).size)
        // The merge preserves the optimistic id so any UI ref by id
        // continues to resolve.
        assertEquals(optimisticId, MessageStore.getMessagesForRoom(room)[0].id)
    }

    @Test
    fun `addMessage merges across existing xmppId vs incoming id`() {
        // Symmetric case: the local message holds xmppId, the incoming
        // server message arrives with that xmppId as its id.
        val room = "room-1@conference.xmpp.example.com"
        MessageStore.addMessage(
            room,
            makeMessage("local-id", pending = true, xmppId = "wire-id")
        )

        val matched = MessageStore.addMessage(
            room,
            makeMessage("wire-id", pending = false)
        )

        assertTrue(matched)
        assertEquals(1, MessageStore.getMessagesForRoom(room).size)
    }

    // --- clear* --------------------------------------------------------

    @Test
    fun `clearMessagesForRoom drops only that room`() {
        val a = "room-a@conference.xmpp.example.com"
        val b = "room-b@conference.xmpp.example.com"
        MessageStore.setMessagesForRoom(a, listOf(makeMessage("a1", roomJid = a)))
        MessageStore.setMessagesForRoom(b, listOf(makeMessage("b1", roomJid = b)))

        MessageStore.clearMessagesForRoom(a)
        assertEquals(0, MessageStore.getMessagesForRoom(a).size)
        assertEquals(1, MessageStore.getMessagesForRoom(b).size)
    }

    @Test
    fun `clear empties all rooms`() {
        val a = "room-a@conference.xmpp.example.com"
        val b = "room-b@conference.xmpp.example.com"
        MessageStore.setMessagesForRoom(a, listOf(makeMessage("a1", roomJid = a)))
        MessageStore.setMessagesForRoom(b, listOf(makeMessage("b1", roomJid = b)))

        MessageStore.clear()
        assertEquals(0, MessageStore.getMessagesForRoom(a).size)
        assertEquals(0, MessageStore.getMessagesForRoom(b).size)
    }
}
