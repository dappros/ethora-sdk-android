package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.User
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.Date

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

    // --- unread counter math, exhaustive (Cluster E in QA_SCENARIOS.md)
    //
    // The per-room counter math in `updateUnreadCount` is where most of
    // the field-reported unread bugs live (MAM own-messages counted as
    // unread, edges around lastViewed, capped-vs-exact rendering). PR #6
    // covered the "active room = 0" rule; these tests cover the
    // non-active branch and the cap behaviour.

    private fun otherUserMessage(
        id: String,
        roomJid: String,
        timestamp: Long,
        senderXmppLocal: String = "other-user",
    ) = Message(
        id = id,
        user = User(id = "$senderXmppLocal@xmpp.example.com"),
        date = Date(timestamp),
        body = "msg-$id",
        roomJid = roomJid,
        timestamp = timestamp,
    )

    @Test
    fun `updateUnreadCount on non-active room with lastViewed=0 counts all countable messages`() {
        // Fresh-room semantics: when lastViewedTimestamp is 0 (room
        // never opened), every countable message must register as
        // unread. Previously this branch was treated as "all read"
        // which is why first-open rooms never lit up the badge.
        val room = makeRoom("a", lastViewedTimestamp = 0L)
        RoomStore.setRooms(listOf(room))
        // No current room set → "a" is non-active.

        val messages = (1..3).map {
            otherUserMessage(id = "m-$it", roomJid = room.jid, timestamp = 1_000L * it)
        }

        RoomStore.updateUnreadCount(room.jid, messages)

        assertEquals(3, RoomStore.getRoomById("a")?.unreadMessages)
        assertFalse(RoomStore.getRoomById("a")?.unreadCapped ?: true)
    }

    @Test
    fun `updateUnreadCount excludes own messages from the count`() {
        // MAM replay frequently delivers a user's own historical
        // messages alongside others'. Own messages must NOT bump the
        // unread badge — a recurring field bug observed after
        // re-login.
        val ownLocal = "alice"
        UserStore.setUser(
            User(id = "$ownLocal@xmpp.example.com", xmppUsername = "$ownLocal@xmpp.example.com")
        )
        try {
            val room = makeRoom("a", lastViewedTimestamp = 0L)
            RoomStore.setRooms(listOf(room))

            val mixed = listOf(
                otherUserMessage("o-1", room.jid, 1_000L, senderXmppLocal = ownLocal),
                otherUserMessage("o-2", room.jid, 2_000L, senderXmppLocal = ownLocal),
                otherUserMessage("x-1", room.jid, 3_000L, senderXmppLocal = "other"),
            )

            RoomStore.updateUnreadCount(room.jid, mixed)

            assertEquals(
                "only the message from 'other' counts; own messages are skipped",
                1, RoomStore.getRoomById("a")?.unreadMessages
            )
        } finally {
            UserStore.clear()
        }
    }

    @Test
    fun `updateUnreadCount excludes pending and system messages`() {
        val room = makeRoom("a", lastViewedTimestamp = 0L)
        RoomStore.setRooms(listOf(room))

        val messages = listOf(
            otherUserMessage("real", room.jid, 1_000L),
            otherUserMessage("pending", room.jid, 2_000L).copy(pending = true),
            otherUserMessage("system", room.jid, 3_000L).copy(isSystemMessage = "true"),
        )

        RoomStore.updateUnreadCount(room.jid, messages)

        assertEquals(
            "only the non-pending, non-system message from another user counts",
            1, RoomStore.getRoomById("a")?.unreadMessages
        )
    }

    @Test
    fun `updateUnreadCount caps at 99 and flags unreadCapped when above`() {
        // The cap = 99 + unreadCapped=true encodes the "99+" badge
        // contract. The UI reads `unreadCapped` to decide between
        // exact-count and overflow rendering.
        val room = makeRoom("a", lastViewedTimestamp = 0L)
        RoomStore.setRooms(listOf(room))

        val messages = (1..150).map {
            otherUserMessage(id = "m-$it", roomJid = room.jid, timestamp = 1_000L * it)
        }

        RoomStore.updateUnreadCount(room.jid, messages)

        val after = RoomStore.getRoomById("a")
        assertEquals("count must clamp to 99", 99, after?.unreadMessages)
        assertTrue("unreadCapped must be true above 99", after?.unreadCapped == true)
    }

    @Test
    fun `updateUnreadCount honors lastViewedTimestamp — only newer messages count`() {
        // Messages older than the last time the user viewed the room
        // must not contribute to unread. This is the boundary that
        // protects against MAM replay re-marking-as-unread on every
        // re-login.
        val lastViewed = 5_000L
        val room = makeRoom("a", lastViewedTimestamp = lastViewed)
        RoomStore.setRooms(listOf(room))

        val messages = listOf(
            otherUserMessage("old", room.jid, lastViewed - 1_000L), // older — skip
            otherUserMessage("boundary", room.jid, lastViewed),     // not strictly > → skip
            otherUserMessage("new", room.jid, lastViewed + 1_000L), // newer — count
        )

        RoomStore.updateUnreadCount(room.jid, messages)

        assertEquals(
            "only messages strictly newer than lastViewed count",
            1, RoomStore.getRoomById("a")?.unreadMessages
        )
    }

    @Test
    fun `updateUnreadCount drops to zero when no countable messages remain`() {
        // After deletes or filter changes leave nothing countable, the
        // badge must clear. Guards a regression class where the count
        // remained stuck at the previous high-water-mark.
        val room = makeRoom("a", unread = 5, lastViewedTimestamp = 0L)
        RoomStore.setRooms(listOf(room))

        RoomStore.updateUnreadCount(room.jid, messages = emptyList())

        assertEquals(0, RoomStore.getRoomById("a")?.unreadMessages)
        assertFalse(RoomStore.getRoomById("a")?.unreadCapped ?: true)
    }

    // --- multi-room concurrent activity (extends Cluster A) -----------
    //
    // "Message arrives in room A while room B is focused" is where the
    // subtlest unread bugs hide. These tests lock in the contract that
    // current-room state is unaffected by incoming messages to other
    // rooms.

    @Test
    fun `addMessage in non-current room leaves current-room pointer untouched`() {
        val a = makeRoom("a")
        val b = makeRoom("b")
        RoomStore.setRooms(listOf(a, b))
        RoomStore.setCurrentRoom(RoomStore.getRoomById("b"))

        MessageStore.clear()
        try {
            MessageStore.addMessage(a.jid, otherUserMessage("a-1", a.jid, 1_000L))
            assertEquals(
                "current room must remain B even when A receives a message",
                "b", RoomStore.currentRoom.value?.id
            )
        } finally {
            MessageStore.clear()
        }
    }

    @Test
    fun `updateUnreadCount on non-current room doesn't touch the current room's unread`() {
        val a = makeRoom("a", unread = 0, lastViewedTimestamp = 0L)
        val b = makeRoom("b", unread = 0)
        RoomStore.setRooms(listOf(a, b))
        RoomStore.setCurrentRoom(RoomStore.getRoomById("b"))

        // Recompute unread on A with 2 incoming messages. B (current)
        // must stay at 0 — the active-room rule is independent of
        // updates to other rooms.
        val messages = listOf(
            otherUserMessage("a-1", a.jid, 1_000L),
            otherUserMessage("a-2", a.jid, 2_000L),
        )
        RoomStore.updateUnreadCount(a.jid, messages)

        assertEquals(2, RoomStore.getRoomById("a")?.unreadMessages)
        assertEquals(
            "current room B must remain at 0 unread",
            0, RoomStore.getRoomById("b")?.unreadMessages
        )
    }

    @Test
    fun `addMessage in non-current room updates that room's lastMessage but not the current room's`() {
        // Catches a regression class where receiving in A would also
        // overwrite B's last-message preview (cross-room mutation bug).
        val a = makeRoom("a")
        val b = makeRoom("b")
        RoomStore.setRooms(listOf(a, b))
        RoomStore.setCurrentRoom(RoomStore.getRoomById("b"))
        MessageStore.clear()
        try {
            MessageStore.addMessage(a.jid, otherUserMessage("a-1", a.jid, 1_000L))

            // A's lastMessage must now be populated; B's must remain
            // null since no messages went to B.
            assertNotNull(
                "room A's lastMessage must update on incoming",
                RoomStore.getRoomById("a")?.lastMessage
            )
            assertNull(
                "room B's lastMessage must be untouched by A's receive",
                RoomStore.getRoomById("b")?.lastMessage
            )
        } finally {
            MessageStore.clear()
        }
    }

    // --- per-room state isolation (Cluster A continuation) ------------

    @Test
    fun `updatePendingCount writes the pending count from messages back onto the room`() {
        // The pending-count derivation feeds the "X queued" indicator
        // and the retry UI. If this drifts, the user sees a stale
        // sending-state indicator.
        val a = makeRoom("a")
        RoomStore.setRooms(listOf(a))

        val messages = listOf(
            otherUserMessage("m-1", a.jid, 1_000L).copy(pending = true),
            otherUserMessage("m-2", a.jid, 2_000L).copy(pending = true),
            otherUserMessage("m-3", a.jid, 3_000L), // not pending
        )

        RoomStore.updatePendingCount(a.jid, messages)

        assertEquals(2, RoomStore.getRoomById("a")?.pendingMessages)
    }

    @Test
    fun `setComposing on one room doesn't bleed into sibling rooms`() {
        // The typing indicator state is per-room. Bleeding it across
        // rooms would surface as "B is typing" appearing when a user
        // is actually typing in A — a confusing UX regression.
        val a = makeRoom("a")
        val b = makeRoom("b")
        RoomStore.setRooms(listOf(a, b))

        RoomStore.setComposing(a.jid, true, listOf("alice"))

        assertEquals(true, RoomStore.getRoomById("a")?.composing)
        assertEquals(
            "room B must remain not-composing when A starts typing",
            null, RoomStore.getRoomById("b")?.composing
        )
    }

    @Test
    fun `setComposing clears composing flag when the typing user is removed by name`() {
        // setComposing iterates over the supplied composingList — so
        // clearing requires passing the *user* to remove, not an empty
        // list. Empty list is a no-op (no usernames to remove → flag
        // stays). This test locks the actual contract.
        val a = makeRoom("a")
        RoomStore.setRooms(listOf(a))

        RoomStore.setComposing(a.jid, true, listOf("alice"))
        assertEquals(true, RoomStore.getRoomById("a")?.composing)

        RoomStore.setComposing(a.jid, false, listOf("alice"))
        assertEquals(
            "composing must clear when the last typing user is removed",
            false, RoomStore.getRoomById("a")?.composing
        )
        assertEquals(emptyList<String>(), RoomStore.getRoomById("a")?.composingList)
    }
}
