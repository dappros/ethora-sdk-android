package com.ethora.chat.core.store

import com.ethora.chat.core.models.HistoryPreloadState
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.createRoomFromApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleRoomSupportTest {
    @Test
    fun `buildPlaceholderRoom creates lightweight single room shell`() {
        val room = buildPlaceholderRoom(
            normalizedRoomJid = "room@conference.example.com",
            originalRoomJid = "room",
            titleOverride = "Playground Room"
        )

        assertEquals("room", room.id)
        assertEquals("room@conference.example.com", room.jid)
        assertEquals("Playground Room", room.title)
        assertEquals("Playground Room", room.name)
        assertTrue(room.isPlaceholder)
        assertFalse(room.presenceReady)
        assertEquals(HistoryPreloadState.IDLE, room.historyPreloadState)
    }

    @Test
    fun `mergeSingleRoomPlaceholder preserves runtime flags`() {
        val placeholder = buildPlaceholderRoom(
            normalizedRoomJid = "room@conference.example.com",
            originalRoomJid = "room",
            titleOverride = null
        ).copy(
            presenceReady = true,
            role = "participant"
        )
        val apiRoom = Room(
            id = "api-id",
            jid = "room@conference.example.com",
            name = "API Name",
            title = "API Title",
            isPlaceholder = false,
            presenceReady = false
        )

        val merged = mergeSingleRoomPlaceholder(apiRoom, placeholder)

        assertEquals("api-id", merged.id)
        assertEquals("API Name", merged.name)
        assertEquals("API Title", merged.title)
        assertTrue(merged.presenceReady)
        assertEquals("participant", merged.role)
        assertFalse(merged.isPlaceholder)
    }

    @Test
    fun `mergeSingleRoomPlaceholder preserves unreadBaselineTimestamp from existing room`() {
        val existing = Room(
            id = "room-1",
            jid = "room@conference.example.com",
            name = "Room",
            title = "Room",
            lastViewedTimestamp = 0L,
            unreadBaselineTimestamp = 1_700_000_000_000L
        )
        val apiRoom = Room(
            id = "room-1",
            jid = "room@conference.example.com",
            name = "Room",
            title = "Room",
            lastViewedTimestamp = null,
            unreadBaselineTimestamp = null
        )

        val merged = mergeSingleRoomPlaceholder(apiRoom, existing)

        // Baseline must survive the API-room refresh so updateUnreadCount can
        // compute a non-zero unread count on the next session.
        assertEquals(1_700_000_000_000L, merged.unreadBaselineTimestamp)
        // lastViewedTimestamp=0 is falsy so it falls through to existing (null→0).
        assertEquals(0L, merged.lastViewedTimestamp)
    }

    @Test
    fun `mergeSingleRoomPlaceholder treats zero timestamps as absent (no marker wipe)`() {
        // Field regression: createRoomFromApi hardcodes lastViewedTimestamp=0
        // and unreadBaselineTimestamp=0 (the API never carries read markers).
        // A plain `?:` merge let that non-null 0 overwrite the local markers
        // on every /chats/my refresh — e.g. when the Chat composable remounts
        // after a back-press + relaunch — after which updateUnreadCount's
        // `effectiveBaseline <= 0` guard pinned unread at 0 and
        // addUnreadListener went permanently silent.
        val existing = Room(
            id = "room-1",
            jid = "room@conference.example.com",
            name = "Room",
            title = "Room",
            lastViewedTimestamp = 1_700_000_000_000L,
            unreadBaselineTimestamp = 1_690_000_000_000L
        )
        val apiRoom = createRoomFromApi(
            apiRoom = com.ethora.chat.core.models.ApiRoom(
                name = "room",
                type = com.ethora.chat.core.models.RoomType.GROUP,
                title = "Room",
                _id = "room-1"
            ),
            conferenceDomain = "conference.example.com"
        )

        val merged = mergeSingleRoomPlaceholder(apiRoom, existing)

        assertEquals(1_700_000_000_000L, merged.lastViewedTimestamp)
        assertEquals(1_690_000_000_000L, merged.unreadBaselineTimestamp)
    }

    @Test
    fun `mergeSingleRoomPlaceholder lets a genuine positive marker win`() {
        val existing = Room(
            id = "room-1",
            jid = "room@conference.example.com",
            name = "Room",
            title = "Room",
            lastViewedTimestamp = 1_000L,
            unreadBaselineTimestamp = 1_000L
        )
        val incoming = Room(
            id = "room-1",
            jid = "room@conference.example.com",
            name = "Room",
            title = "Room",
            lastViewedTimestamp = 2_000L,
            unreadBaselineTimestamp = 3_000L
        )

        val merged = mergeSingleRoomPlaceholder(incoming, existing)

        assertEquals(2_000L, merged.lastViewedTimestamp)
        assertEquals(3_000L, merged.unreadBaselineTimestamp)
    }

    @Test
    fun `findRoomByJid matches by full and bare jid`() {
        val room = Room(id = "1", jid = "room@conference.example.com", name = "Room", title = "Room")

        assertEquals(room, findRoomByJid(listOf(room), "room@conference.example.com"))
        assertEquals(room, findRoomByJid(listOf(room), "room"))
        assertNull(findRoomByJid(listOf(room), "other"))
    }
}
