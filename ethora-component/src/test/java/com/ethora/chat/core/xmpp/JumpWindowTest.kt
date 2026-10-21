package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

/** Locating a message in the archive: id and content matching, window fetch, time lookup. */
class JumpWindowTest {

    private val base = 1_704_067_200_000_000L

    private fun archived(index: Int, body: String = "message $index") = Message(
        id = (base + index * 1000L).toString(),
        user = User(id = "u1"),
        date = Date((base + index * 1000L) / 1000L),
        body = body,
        roomJid = "room@conference.host",
        timestamp = (base + index * 1000L) / 1000L
    )

    /** Fake archive: ascending rows; answers the same way the server pages (exclusive cursors, fin complete). */
    private class FakeArchive(private val rows: List<Message>, private val ok: Boolean = true) : HistoryWindowClient {
        override suspend fun getHistoryWindow(roomJid: String, max: Int, cursor: WindowCursor): HistoryWindowPage {
            if (!ok) return HistoryWindowPage.FAILED
            val picked: List<Message>
            when {
                cursor.before != null -> {
                    val below = rows.filter { it.id.toLong() < cursor.before }
                    picked = below.takeLast(max)
                    return page(picked, complete = below.size <= max)
                }
                cursor.after != null -> {
                    val above = rows.filter { it.id.toLong() > cursor.after }
                    picked = above.take(max)
                    return page(picked, complete = above.size <= max)
                }
                cursor.start != null && cursor.end != null -> {
                    val s = Instant.parse(cursor.start).toEpochMilli()
                    val e = Instant.parse(cursor.end).toEpochMilli()
                    val inRange = rows.filter { it.date.time in s..e }
                    return page(inRange.take(max), complete = inRange.size <= max)
                }
            }
            return page(rows.takeLast(max), complete = rows.size <= max)
        }

        private fun page(messages: List<Message>, complete: Boolean) = HistoryWindowPage(
            ok = true,
            messages = messages,
            complete = complete,
            first = messages.firstOrNull()?.id?.toLong(),
            last = messages.lastOrNull()?.id?.toLong()
        )
    }

    private val rows = (0 until 60).map { archived(it) }

    @Test
    fun find_by_archive_id_then_by_client_id() {
        val list = listOf(archived(0), archived(1).copy(id = "send-1", xmppId = "client-1"))
        assertEquals(1, findMessageIndex(list, listOf("send-1")))
        assertEquals(1, findMessageIndex(list, listOf("client-1")))
        assertEquals(-1, findMessageIndex(list, listOf("nope")))
    }

    @Test
    fun find_by_content_needs_the_same_text_within_five_seconds() {
        val list = listOf(archived(10, body = "hello"))
        val near = Instant.ofEpochMilli(list[0].date.time + 2_000).toString()
        val far = Instant.ofEpochMilli(list[0].date.time + 60_000).toString()
        assertEquals(0, findMessageIndex(list, emptyList(), JumpContent(near, " hello ")))
        assertEquals(-1, findMessageIndex(list, emptyList(), JumpContent(far, "hello")))
        assertEquals(-1, findMessageIndex(list, emptyList(), JumpContent(near, "other")))
    }

    @Test
    fun only_archive_shaped_ids_are_candidates() {
        assertEquals(listOf("1704067200000000"), mamIdCandidates(listOf("send-x", "1704067200000000", "1704067200000000", "42")))
    }

    @Test
    fun window_around_a_target_is_ascending_and_centred_on_it() = runBlocking {
        val target = rows[30]
        val result = loadJumpWindow(FakeArchive(rows), "room@conference.host", listOf(target.id))
        val window = (result as JumpWindowResult.Found).window
        assertEquals(target.id, window.targetId)
        assertEquals(rows.subList(20, 31).map { it.id }, window.messages.map { it.id }.take(11))
        assertTrue(window.messages.zipWithNext().all { (a, b) -> a.id.toLong() < b.id.toLong() })
        assertTrue(window.hasOlder)
        assertTrue(window.hasNewer)
    }

    @Test
    fun target_near_the_start_of_the_archive_has_nothing_older() = runBlocking {
        val result = loadJumpWindow(FakeArchive(rows), "room@conference.host", listOf(rows[2].id))
        val window = (result as JumpWindowResult.Found).window
        assertFalse(window.hasOlder)
    }

    @Test
    fun id_that_is_not_in_the_archive_is_missing_not_unavailable() = runBlocking {
        val result = loadJumpWindow(FakeArchive(rows), "room@conference.host", listOf("1704067200999999"))
        assertEquals(JumpWindowResult.Missing, result)
    }

    @Test
    fun failed_request_is_unavailable() = runBlocking {
        val result = loadJumpWindow(FakeArchive(rows, ok = false), "room@conference.host", listOf(rows[5].id))
        assertEquals(JumpWindowResult.Unavailable, result)
    }

    @Test
    fun message_without_an_archive_id_is_found_by_its_time_and_text() = runBlocking {
        val wanted = rows[40]
        val content = JumpContent(
            createdAt = Instant.ofEpochMilli(wanted.date.time).toString(),
            body = wanted.body
        )
        val result = loadJumpWindow(FakeArchive(rows), "room@conference.host", listOf("send-local"), content)
        val window = (result as JumpWindowResult.Found).window
        assertEquals(wanted.id, window.targetId)
    }

    @Test
    fun newer_and_older_pages_move_their_cursors() = runBlocking {
        val archive = FakeArchive(rows)
        val window = (loadJumpWindow(archive, "room@conference.host", listOf(rows[30].id)) as JumpWindowResult.Found).window
        val older = loadOlderWindowPage(archive, window)!!
        assertEquals(20, older.messages.size)
        assertTrue(older.messages.last().id.toLong() < window.messages.first().id.toLong())
        val newer = loadNewerWindowPage(archive, window)!!
        assertTrue(newer.messages.first().id.toLong() > window.messages.last().id.toLong())
    }

    @Test
    fun reply_parent_is_read_from_a_bare_id_or_json_and_only_for_replies() {
        val reply = archived(1).copy(isReply = true, mainMessage = "1704067200000000")
        assertEquals("1704067200000000", replyParentIdOf(reply))
        val json = reply.copy(mainMessage = """{"id":"1704067200005000","text":"hi"}""")
        assertEquals("1704067200005000", replyParentIdOf(json))
        assertNull(replyParentIdOf(reply.copy(isReply = false)))
        assertNull(replyParentIdOf(reply.copy(mainMessage = null)))
    }
}
