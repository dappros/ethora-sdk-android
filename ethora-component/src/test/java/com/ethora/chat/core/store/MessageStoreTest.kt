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

    // --- rapid-send + duplication patterns (Cluster D in QA_SCENARIOS.md)
    //
    // These tests target the cluster of field reports where bulk sends
    // under degraded networks leave duplicate or out-of-order bubbles in
    // the list. The bidirectional id/xmppId match in `addMessage` and
    // `addMessages` is load-bearing for this contract.

    @Test
    fun `addMessage 10 unique ids appends all 10 in order`() {
        val room = "room-1@conference.xmpp.example.com"
        repeat(10) { i ->
            // Distinct timestamps preserve a deterministic UI order under
            // sortedForUi — without unique timestamps the sort falls
            // through to id comparison and the test would assert on
            // string ordering rather than insertion order.
            val msg = makeMessage("m-$i", body = "msg-$i").copy(
                timestamp = 1_000L + i,
                date = Date(1_000L + i)
            )
            MessageStore.addMessage(room, msg)
        }

        val read = MessageStore.getMessagesForRoom(room)
        assertEquals(10, read.size)
        assertEquals(
            "rapid send must preserve chronological order",
            (0 until 10).map { "m-$it" },
            read.map { it.id }
        )
    }

    @Test
    fun `addMessage mixed unique + duplicates ends with only the unique set`() {
        // Field-bug shape: under network blip, the send pipeline can
        // re-emit the same id multiple times — once optimistically, again
        // from MAM replay, again from server echo. Each redundant call
        // must NOT add a new bubble.
        val room = "room-1@conference.xmpp.example.com"
        val ids = listOf("a", "b", "c", "d", "e")
        ids.forEach { MessageStore.addMessage(room, makeMessage(it, pending = false)) }
        // Duplicate fire — second pass over the same ids.
        ids.forEach { MessageStore.addMessage(room, makeMessage(it, pending = false)) }

        val read = MessageStore.getMessagesForRoom(room)
        assertEquals(
            "duplicate id fire must not increase the bubble count",
            5, read.size
        )
    }

    @Test
    fun `addMessage cross-room — adding to A leaves B untouched`() {
        // Catches a regression class where adding to room A would
        // accidentally clobber the `_messages` map's B entry through
        // an in-place mutation. The Map.toMutableMap() guard in
        // addMessage is what protects this — the test locks that
        // protection in.
        val a = "room-a@conference.xmpp.example.com"
        val b = "room-b@conference.xmpp.example.com"
        MessageStore.setMessagesForRoom(
            b,
            listOf(
                makeMessage("b1", roomJid = b),
                makeMessage("b2", roomJid = b),
            )
        )

        // Drive 5 sends into A.
        repeat(5) { i ->
            MessageStore.addMessage(a, makeMessage("a-$i", roomJid = a))
        }

        assertEquals(5, MessageStore.getMessagesForRoom(a).size)
        assertEquals(
            "messages in room B must be unaffected by sends to room A",
            listOf("b1", "b2"),
            MessageStore.getMessagesForRoom(b).map { it.id }
        )
    }

    @Test
    fun `addMessages bulk insert dedups and preserves order by timestamp`() {
        // `addMessages` is the bulk path used by history/MAM ingestion.
        // It must dedup against existing rows AND ingest a mixed batch
        // in a single observable update without losing intra-batch
        // ordering.
        val room = "room-1@conference.xmpp.example.com"

        // Seed with one existing message.
        MessageStore.addMessage(
            room,
            makeMessage("seed").copy(timestamp = 5_000L, date = Date(5_000L))
        )

        // Bulk-add: 4 new + 1 duplicate of the seed.
        val batch = listOf(
            makeMessage("b1").copy(timestamp = 1_000L, date = Date(1_000L)),
            makeMessage("b2").copy(timestamp = 2_000L, date = Date(2_000L)),
            makeMessage("seed").copy(timestamp = 5_000L, date = Date(5_000L)), // dup
            makeMessage("b3").copy(timestamp = 3_000L, date = Date(3_000L)),
            makeMessage("b4").copy(timestamp = 4_000L, date = Date(4_000L)),
        )
        MessageStore.addMessages(room, batch)

        val read = MessageStore.getMessagesForRoom(room)
        assertEquals(
            "duplicate must be dropped — final size is 5 (seed + 4 unique)",
            5, read.size
        )
        assertEquals(
            "messages sorted by timestamp ascending",
            listOf("b1", "b2", "b3", "b4", "seed"),
            read.map { it.id }
        )
    }

    // --- cache contract (Cluster F in QA_SCENARIOS.md) ----------------

    @Test
    fun `setMessagesForRoom then getMessagesForRoom round-trips content`() {
        // The fast-load contract: when the persistence layer hydrates a
        // room from the on-disk cache, it does so via setMessagesForRoom.
        // The very next read must observe the seeded messages without
        // a network round-trip. This is what users experience as
        // "instant open" on a previously-visited room.
        val room = "room-1@conference.xmpp.example.com"
        val seeded = listOf(
            makeMessage("c1", body = "cached one"),
            makeMessage("c2", body = "cached two"),
            makeMessage("c3", body = "cached three"),
        )
        MessageStore.setMessagesForRoom(room, seeded)

        val read = MessageStore.getMessagesForRoom(room)
        assertEquals(seeded.size, read.size)
        assertEquals(
            "cached content survives the set→get round-trip exactly",
            seeded.map { it.id }.toSet(),
            read.map { it.id }.toSet()
        )
    }

    @Test
    fun `markMessageAsDeleted keeps the row but flips isDeleted true`() {
        // Catches the failure mode where deleting a message removed the
        // row entirely (causing surrounding messages to renumber and
        // unread cursors to drift) instead of leaving a tombstone in
        // place.
        val room = "room-1@conference.xmpp.example.com"
        MessageStore.addMessage(room, makeMessage("doomed", body = "delete me"))
        MessageStore.addMessage(room, makeMessage("survivor", body = "keep me"))

        MessageStore.markMessageAsDeleted(room, "doomed")

        val read = MessageStore.getMessagesForRoom(room)
        assertEquals(
            "deleted message must remain in the list as a tombstone",
            2, read.size
        )
        val doomed = read.first { it.id == "doomed" }
        assertEquals(true, doomed.isDeleted)
    }
}
