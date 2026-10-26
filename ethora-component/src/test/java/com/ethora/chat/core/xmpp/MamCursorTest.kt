package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Paging cursor rules for MAM history (web xmppClient.mamCursor tests, ported). */
class MamCursorTest {

    private fun msg(id: String, archiveId: String? = null, body: String = "b", ms: Long = 0L) = Message(
        id = id,
        user = User(id = "u1"),
        date = Date(ms),
        body = body,
        roomJid = "room@conference.host",
        timestamp = ms,
        archiveId = archiveId
    )

    @Test
    fun latest_page_fin_never_pulls_an_older_cursor_forward() {
        // A page whose first row is not older than the cursor is not progress.
        val state = advanceOlderCursor(before = 1_500_000_000_000_000L, ok = true, complete = false, first = 1_700_000_000_000_000L)
        assertEquals(1_500_000_000_000_000L, state.cursor)
        assertTrue(state.exhausted)
    }

    @Test
    fun older_page_moves_the_cursor_back_to_its_first_row() {
        val state = advanceOlderCursor(before = 1_700_000_000_000_000L, ok = true, complete = false, first = 1_600_000_000_000_000L)
        assertEquals(1_600_000_000_000_000L, state.cursor)
        assertFalse(state.exhausted)
    }

    @Test
    fun complete_fin_means_nothing_older_exists() {
        val state = advanceOlderCursor(before = 1_700_000_000_000_000L, ok = true, complete = true, first = 1_600_000_000_000_000L)
        assertTrue(state.exhausted)
    }

    @Test
    fun failed_page_keeps_the_cursor_and_is_not_exhaustion_by_itself() {
        val state = advanceOlderCursor(before = 1_700_000_000_000_000L, ok = false, complete = false, first = null)
        assertEquals(1_700_000_000_000_000L, state.cursor)
        assertFalse(state.exhausted)
    }

    @Test
    fun cursor_is_the_archive_id_and_never_a_timestamp() {
        // Optimistic ids and millisecond timestamps are not archive ids.
        assertNull(archiveCursorOf(msg("send-text-message-1704067200000", ms = 1704067200000L)))
        assertEquals(
            1_704_067_200_000_123L,
            archiveCursorOf(msg("1704067200000123", ms = 1704067200000L))
        )
        // A stored archive id wins over the display id.
        assertEquals(
            1_704_067_200_000_999L,
            archiveCursorOf(msg("pending-1", archiveId = "1704067200000999"))
        )
    }

    @Test
    fun oldest_cursor_is_the_smallest_archive_id_in_the_list() {
        val list = listOf(
            msg("1704067200000300"),
            msg("send-x"),
            msg("1704067200000100"),
            msg("1704067200000200")
        )
        assertEquals(1_704_067_200_000_100L, oldestArchiveCursor(list))
        assertNull(oldestArchiveCursor(listOf(msg("send-x"))))
    }
}
