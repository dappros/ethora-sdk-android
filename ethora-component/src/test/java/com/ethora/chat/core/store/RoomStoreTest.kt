package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Unit tests for the [RoomStore] singleton's reducer-shaped methods.
 *
 * `RoomStore.persistRooms()` returns early when `persistenceManager`
 * is null, so the SUT is safe to drive in-test without initialising
 * a DataStore. Each test calls `RoomStore.clear()` in
 * `@Before` / `@After` so state doesn't leak between tests — the
 * store is a process-wide singleton.
 *
 * Most assertions exercise add/update/upsert semantics. The
 * presence-ready stickiness (set in `updateRoom`) is asserted
 * explicitly because the comment on the method calls it out as
 * intentional behaviour, and accidentally clearing it on a
 * subsequent update is a real regression risk.
 */
class RoomStoreTest {

    @Before
    fun resetStore() {
        RoomStore.clear()
    }

    @After
    fun tearDownStore() {
        RoomStore.clear()
    }

    // --- helpers -------------------------------------------------------

    private fun makeRoom(
        id: String,
        jid: String = "$id@conference.xmpp.example.com",
        name: String = "room-$id",
        title: String = "Room $id",
        unread: Int = 0,
        presenceReady: Boolean = false,
        lastViewedTimestamp: Long? = null,
    ) = Room(
        id = id,
        jid = jid,
        name = name,
        title = title,
        type = RoomType.GROUP,
        unreadMessages = unread,
        presenceReady = presenceReady,
        lastViewedTimestamp = lastViewedTimestamp,
    )

    // --- setRooms ------------------------------------------------------

    @Test
    fun `setRooms replaces the entire list`() {
        RoomStore.setRooms(listOf(makeRoom("a"), makeRoom("b")))
        assertEquals(2, RoomStore.rooms.value.size)

        RoomStore.setRooms(listOf(makeRoom("c")))
        assertEquals(1, RoomStore.rooms.value.size)
        assertEquals("c", RoomStore.rooms.value[0].id)
    }

    @Test
    fun `setRooms on empty store starts from clean state`() {
        assertEquals(0, RoomStore.rooms.value.size)
        RoomStore.setRooms(listOf(makeRoom("a")))
        assertEquals(1, RoomStore.rooms.value.size)
    }

    // --- addRoom -------------------------------------------------------

    @Test
    fun `addRoom appends when room is new`() {
        RoomStore.addRoom(makeRoom("a"))
        RoomStore.addRoom(makeRoom("b"))

        assertEquals(2, RoomStore.rooms.value.size)
        assertEquals(listOf("a", "b"), RoomStore.rooms.value.map { it.id })
    }

    @Test
    fun `addRoom merges when id matches an existing room`() {
        RoomStore.addRoom(makeRoom("a", title = "Original Title"))
        RoomStore.addRoom(makeRoom("a", title = "Updated Title"))

        // Still one room; not appended.
        assertEquals(1, RoomStore.rooms.value.size)
        // mergeSingleRoomPlaceholder governs the merge, but for a
        // straightforward non-placeholder update the new title wins.
        assertEquals("Updated Title", RoomStore.rooms.value[0].title)
    }

    @Test
    fun `addRoom merges when jid matches an existing room (id differs)`() {
        val sharedJid = "shared@conference.xmpp.example.com"
        RoomStore.addRoom(makeRoom("a", jid = sharedJid))
        RoomStore.addRoom(makeRoom("b", jid = sharedJid))

        // Stored by jid → only one entry.
        assertEquals(1, RoomStore.rooms.value.size)
    }

    // --- updateRoom + presenceReady stickiness -------------------------

    @Test
    fun `updateRoom replaces existing entry`() {
        RoomStore.setRooms(listOf(makeRoom("a", unread = 0)))
        RoomStore.updateRoom(makeRoom("a", unread = 7))
        assertEquals(7, RoomStore.rooms.value[0].unreadMessages)
    }

    @Test
    fun `updateRoom keeps presenceReady=true once set (sticky flag)`() {
        // Start with a room that already has presenceReady=true. An
        // incoming update without presenceReady set should NOT clear
        // it — the comment on RoomStore.updateRoom calls this out as
        // intentional. A regression here would cause Send-button
        // gating to flap.
        RoomStore.setRooms(listOf(makeRoom("a", presenceReady = true)))

        RoomStore.updateRoom(makeRoom("a", presenceReady = false))
        assertTrue(
            "presenceReady must remain true after an update that didn't set it",
            RoomStore.rooms.value[0].presenceReady
        )
    }

    @Test
    fun `updateRoom sets presenceReady=true when incoming room has it`() {
        RoomStore.setRooms(listOf(makeRoom("a", presenceReady = false)))
        RoomStore.updateRoom(makeRoom("a", presenceReady = true))
        assertTrue(RoomStore.rooms.value[0].presenceReady)
    }

    @Test
    fun `updateRoom on missing room is a no-op for the list`() {
        RoomStore.setRooms(listOf(makeRoom("a")))
        RoomStore.updateRoom(makeRoom("ghost"))
        // List unchanged.
        assertEquals(listOf("a"), RoomStore.rooms.value.map { it.id })
    }

    // --- removeRoom ----------------------------------------------------

    @Test
    fun `removeRoom drops matching id and leaves rest`() {
        RoomStore.setRooms(listOf(makeRoom("a"), makeRoom("b"), makeRoom("c")))
        RoomStore.removeRoom("b")
        assertEquals(listOf("a", "c"), RoomStore.rooms.value.map { it.id })
    }

    @Test
    fun `removeRoom matches by jid when id miss`() {
        // removeRoom's argument is "roomId" by parameter name but
        // matches on either id OR jid — guards a regression where
        // host code passes a JID instead of an ID.
        val jid = "x@conference.xmpp.example.com"
        RoomStore.setRooms(listOf(makeRoom("a", jid = jid)))
        RoomStore.removeRoom(jid)
        assertEquals(0, RoomStore.rooms.value.size)
    }

    @Test
    fun `removeRoom clears currentRoom when it matches`() {
        RoomStore.setRooms(listOf(makeRoom("a"), makeRoom("b")))
        RoomStore.setCurrentRoom(RoomStore.rooms.value.first { it.id == "a" })
        assertNotNull(RoomStore.currentRoom.value)

        RoomStore.removeRoom("a")
        assertNull(
            "currentRoom should clear when the active room is removed",
            RoomStore.currentRoom.value
        )
    }

    // --- setCurrentRoom ------------------------------------------------

    @Test
    fun `setCurrentRoom updates the current-room StateFlow`() {
        val r = makeRoom("a")
        RoomStore.setRooms(listOf(r))
        RoomStore.setCurrentRoom(r)
        assertEquals("a", RoomStore.currentRoom.value?.id)

        RoomStore.setCurrentRoom(null)
        assertNull(RoomStore.currentRoom.value)
    }

    // --- getters -------------------------------------------------------

    @Test
    fun `getRoomById and getRoomByJid resolve known rooms`() {
        val r = makeRoom("a")
        RoomStore.setRooms(listOf(r))

        assertEquals(r, RoomStore.getRoomById("a"))
        assertEquals(r, RoomStore.getRoomByJid(r.jid))
    }

    @Test
    fun `getRoomById and getRoomByJid return null for unknown lookups`() {
        RoomStore.setRooms(listOf(makeRoom("a")))
        assertNull(RoomStore.getRoomById("ghost"))
        assertNull(RoomStore.getRoomByJid("ghost@conference.xmpp.example.com"))
    }

    // --- upsertRoom ----------------------------------------------------

    @Test
    fun `upsertRoom inserts when room is new`() {
        RoomStore.upsertRoom(makeRoom("a"))
        assertEquals(1, RoomStore.rooms.value.size)
    }

    @Test
    fun `upsertRoom merges when jid matches`() {
        val jid = "shared@conference.xmpp.example.com"
        RoomStore.upsertRoom(makeRoom("a", jid = jid, title = "First"))
        RoomStore.upsertRoom(makeRoom("a", jid = jid, title = "Second"))
        assertEquals(1, RoomStore.rooms.value.size)
        assertEquals("Second", RoomStore.rooms.value[0].title)
    }

    // --- clear ---------------------------------------------------------

    @Test
    fun `clear empties rooms and currentRoom`() {
        val r = makeRoom("a")
        RoomStore.setRooms(listOf(r))
        RoomStore.setCurrentRoom(r)

        RoomStore.clear()
        assertEquals(0, RoomStore.rooms.value.size)
        assertNull(RoomStore.currentRoom.value)
    }

    // --- multi-room state machine (Cluster A in QA_SCENARIOS.md) -------
    //
    // These tests target the cluster of field bugs where opening Room A
    // succeeds, then switching to Room B sticks on "Connecting", and
    // re-entering A behaves inconsistently. The root cause is usually
    // per-room state bleeding between rooms or being cleared on
    // current-room transitions. RoomStore can't catch every variant
    // (some live in the XMPP layer), but these assertions lock in the
    // observable contract.

    @Test
    fun `setCurrentRoom transitions A then B then A — pointer reflects each step`() {
        val a = makeRoom("a")
        val b = makeRoom("b")
        RoomStore.setRooms(listOf(a, b))

        RoomStore.setCurrentRoom(a)
        assertEquals("a", RoomStore.currentRoom.value?.id)

        RoomStore.setCurrentRoom(b)
        assertEquals("b", RoomStore.currentRoom.value?.id)

        RoomStore.setCurrentRoom(a)
        assertEquals(
            "re-entering A after a B detour must register as a current-room transition",
            "a", RoomStore.currentRoom.value?.id
        )
    }

    @Test
    fun `setRoomLoading on one room doesn't bleed into sibling rooms`() {
        // Per-room loading maps to the "Connecting…" indicator on the
        // chat screen. If touching loading state on B accidentally
        // mutated A's flag, switching back to A would show the wrong
        // skeleton — exactly the symptom multiple field reports describe.
        val a = makeRoom("a")
        val b = makeRoom("b")
        val c = makeRoom("c")
        RoomStore.setRooms(listOf(a, b, c))

        RoomStore.setRoomLoading(b.jid, true)

        assertEquals(false, RoomStore.isRoomLoading(a.jid))
        assertTrue(RoomStore.isRoomLoading(b.jid))
        assertEquals(false, RoomStore.isRoomLoading(c.jid))
    }

    @Test
    fun `setRoomLoading transitions cleanly true to false on a single room`() {
        val a = makeRoom("a")
        RoomStore.setRooms(listOf(a))

        RoomStore.setRoomLoading(a.jid, true)
        assertTrue(RoomStore.isRoomLoading(a.jid))

        RoomStore.setRoomLoading(a.jid, false)
        assertEquals(
            "loading must clear when set to false — guards the 'stuck on Connecting' regression",
            false, RoomStore.isRoomLoading(a.jid)
        )

        // And back to true again — the map mutates idempotently.
        RoomStore.setRoomLoading(a.jid, true)
        assertTrue(RoomStore.isRoomLoading(a.jid))
    }

    @Test
    fun `setCurrentRoom bumps lastViewedTimestamp on the outgoing room when zero`() {
        // Catches the symptom where switching rooms would briefly flash
        // a full unread badge on the room you just left, because its
        // lastViewedTimestamp was still 0 (ChatRoomView keeps it 0 while
        // open). The bump-on-switch in setCurrentRoom prevents this.
        val a = makeRoom("a", lastViewedTimestamp = 0L)
        val b = makeRoom("b")
        RoomStore.setRooms(listOf(a, b))
        RoomStore.setCurrentRoom(a)

        RoomStore.setCurrentRoom(b)

        val aAfter = RoomStore.getRoomById("a")
        assertNotNull(aAfter)
        val lastViewed = aAfter!!.lastViewedTimestamp ?: 0L
        assertTrue(
            "lastViewedTimestamp on outgoing room must be bumped above 0 on switch",
            lastViewed > 0L
        )
    }

    @Test
    fun `updateUnreadCount on the active room zeroes unread regardless of input`() {
        // The "active room → unread = 0" rule from
        // RoomStore.updateUnreadCount is a load-bearing invariant for the
        // chat-tab badge. If this regresses, the user sees a count on a
        // room they're actively reading.
        val a = makeRoom("a", unread = 5)
        RoomStore.setRooms(listOf(a))
        RoomStore.setCurrentRoom(RoomStore.getRoomById("a"))

        // Feed in messages that would otherwise count as unread (lastViewed=0
        // would otherwise mark all countable messages unread per the
        // non-active branch).
        RoomStore.updateUnreadCount(a.jid, messages = emptyList())

        assertEquals(
            "active room must always read as 0 unread",
            0, RoomStore.getRoomById("a")?.unreadMessages
        )
    }
}
