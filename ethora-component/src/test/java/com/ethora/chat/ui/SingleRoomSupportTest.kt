package com.ethora.chat.core.store

import com.ethora.chat.core.models.HistoryPreloadState
import com.ethora.chat.core.models.Room
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
    fun `findRoomByJid matches by full and bare jid`() {
        val room = Room(id = "1", jid = "room@conference.example.com", name = "Room", title = "Room")

        assertEquals(room, findRoomByJid(listOf(room), "room@conference.example.com"))
        assertEquals(room, findRoomByJid(listOf(room), "room"))
        assertNull(findRoomByJid(listOf(room), "other"))
    }
}
