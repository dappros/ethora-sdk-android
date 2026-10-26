package com.ethora.chat.core.search

import com.ethora.chat.core.networking.MessageSearchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Pure-JVM tests for message search: page parsing, filters, snippet, sender and people matching. */
class MessageSearchLogicTest {

    private fun raw(
        id: String,
        body: String? = "hello world",
        deletedAt: String? = null,
        from: String = "app_u1@xmpp.host",
        fromUserId: String = "u1"
    ) = RawMessageSearchHit(
        _id = id, chatId = "room1", chatType = "group", room = "room1@conference.host",
        from = from, fromUserId = fromUserId, body = body, messageId = "m$id",
        stanzaId = "s$id", createdAt = "2026-07-14T10:00:00Z", deletedAt = deletedAt
    )

    @Test
    fun page_drops_deleted_and_empty_hits_but_counts_server_rows_for_next_offset() {
        val dto = MessageSearchResponseDto(
            items = listOf(raw("1"), raw("2", deletedAt = "2026-07-15T00:00:00Z"), raw("3", body = ""), raw("4")),
            total = 40, offset = 0, limit = 20
        )
        val page = parseMessageSearchPage(dto, requestedOffset = 0, requestedLimit = 20)
        assertEquals(listOf("1", "4"), page.items.map { it.id })
        // Four server rows were consumed, so the next page starts at 4, not at the kept count (2).
        assertEquals(4, page.nextOffset)
        assertEquals(40, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun hits_with_no_ids_get_distinct_keys() {
        val a = parseMessageSearchPage(MessageSearchResponseDto(items = listOf(raw("", body = "a"), raw("", body = "b"))), 0, 20)
        val keys = a.items.map { it.key() }
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun merge_skips_hits_already_shown() {
        val first = parseMessageSearchPage(MessageSearchResponseDto(items = listOf(raw("1"), raw("2"))), 0, 20).items
        val next = parseMessageSearchPage(MessageSearchResponseDto(items = listOf(raw("2"), raw("3"))), 2, 20).items
        assertEquals(listOf("1", "2", "3"), mergeSearchHits(first, next).map { it.id })
    }

    @Test
    fun day_bounds_cover_the_whole_picked_day() {
        val start = dayStartIso("2026-07-14", ZoneOffset.UTC)
        val end = dayEndIso("2026-07-14", ZoneOffset.UTC)
        assertEquals("2026-07-14T00:00:00Z", start)
        assertEquals("2026-07-14T23:59:59.999Z", end)
    }

    @Test
    fun invalid_day_yields_no_bound() {
        assertNull(dayStartIso("14.07.2026"))
        assertNull(dayEndIso(""))
    }

    @Test
    fun backwards_range_is_detected_only_when_both_days_are_set() {
        assertTrue(isBackwardsDateRange("2026-07-15", "2026-07-14"))
        assertFalse(isBackwardsDateRange("2026-07-14", "2026-07-15"))
        assertFalse(isBackwardsDateRange("", "2026-07-14"))
    }

    @Test
    fun snippet_highlights_the_match_case_insensitively_and_treats_query_as_text() {
        val parts = buildSnippet("Please SEND the file.", "send")
        assertEquals(listOf("Please ", "SEND", " the file."), parts.map { it.text })
        assertEquals(listOf(false, true, false), parts.map { it.match })
        // Regex metacharacters are plain characters.
        val literal = buildSnippet("price (approx.) is high", "(approx.)")
        assertTrue(literal.any { it.match && it.text == "(approx.)" })
    }

    @Test
    fun snippet_of_a_long_body_is_cut_around_the_match_with_ellipses() {
        val body = "word ".repeat(60) + "needle" + " word".repeat(60)
        val parts = buildSnippet(body, "needle")
        val joined = parts.joinToString("") { it.text }
        assertTrue(joined.startsWith("…"))
        assertTrue(joined.endsWith("…"))
        assertTrue(parts.any { it.match && it.text == "needle" })
    }

    @Test
    fun match_people_ranks_prefix_matches_first_and_skips_duplicates() {
        val people = listOf(
            "u1" to "Anna Bell",
            "u2" to "Joanna",
            "u3" to "Anna Ruiz",
            "u1" to "Anna Bell"
        )
        assertEquals(listOf("u1", "u3", "u2"), matchPeople(people, "ann").map { it.id })
        assertTrue(matchPeople(people, "   ").isEmpty())
    }

    @Test
    fun sender_is_you_for_own_jid_and_named_from_member_id() {
        val hit = MessageSearchHit(
            id = "1", chatId = "room1", chatType = "group", room = "room1@conference.host",
            from = "app_me@xmpp.host", fromUserId = "me", body = "x", messageId = "m", stanzaId = "s", createdAt = ""
        )
        assertEquals(SearchSender.Self, resolveSearchSender(hit, emptyMap(), emptyMap(), "app_me@xmpp.host"))
        val other = hit.copy(from = "app_u9@xmpp.host", fromUserId = "u9")
        assertEquals(
            SearchSender.Named("Ola"),
            resolveSearchSender(other, mapOf("u9" to "Ola"), emptyMap(), "app_me@xmpp.host")
        )
        assertEquals(SearchSender.Unknown, resolveSearchSender(other, emptyMap(), emptyMap(), "app_me@xmpp.host"))
    }

    @Test
    fun api_origin_strips_a_trailing_version_segment() {
        assertEquals("https://api.chat.ethora.com", MessageSearchService.apiOriginOf("https://api.chat.ethora.com/v1"))
        assertEquals("https://api.chat.ethora.com", MessageSearchService.apiOriginOf("https://api.chat.ethora.com/v2/"))
        assertEquals(
            "https://api.chat.ethora.com/v2/apps/app+1/messages/search",
            MessageSearchService.searchUrl("https://api.chat.ethora.com/v1", "app 1")
        )
    }
}
