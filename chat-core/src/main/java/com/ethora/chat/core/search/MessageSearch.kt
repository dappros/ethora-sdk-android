package com.ethora.chat.core.search

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Message search model and pure helpers.
 * Port of web messageSearch.api.ts, MessageSearchModal/searchFilters.ts,
 * snippet.ts, resolveSender.ts and useMessageSearch.ts (the rules, not the UI).
 */

const val MESSAGE_SEARCH_PAGE_SIZE = 20
const val MESSAGE_SEARCH_MIN_QUERY_LENGTH = 2
const val MESSAGE_SEARCH_DEBOUNCE_MS = 300L

/** One archive hit. `from` is the sender's full JID WITH the xmpp host. */
data class MessageSearchHit(
    /** The archive row's own id: the only identifier every hit is guaranteed to have. */
    val id: String,
    /** Room name, the local part of the room JID. */
    val chatId: String,
    val chatType: String,
    /** Full room JID. */
    val room: String,
    val from: String,
    val fromUserId: String,
    val body: String,
    val messageId: String,
    val stanzaId: String,
    val createdAt: String
)

data class MessageSearchPage(
    val items: List<MessageSearchHit>,
    /** Total matches on the server, not the size of this page. */
    val total: Int,
    val offset: Int,
    val limit: Int,
    /** Where the next page starts, counted in SERVER rows (deleted rows are dropped from [items]). */
    val nextOffset: Int
) {
    val hasMore: Boolean get() = nextOffset < total
}

data class MessageSearchParams(
    val q: String,
    val chatId: String? = null,
    val fromUserId: String? = null,
    /** ISO 8601 instants; the server rejects anything else. */
    val since: String? = null,
    val until: String? = null,
    val limit: Int = MESSAGE_SEARCH_PAGE_SIZE,
    val offset: Int = 0
)

/** A hit as the server sends it, before filtering. Field names match the JSON. */
data class RawMessageSearchHit(
    val _id: String? = null,
    val chatId: String? = null,
    val chatType: String? = null,
    val room: String? = null,
    val from: String? = null,
    val fromUserId: String? = null,
    val body: String? = null,
    val messageId: String? = null,
    val stanzaId: String? = null,
    val createdAt: String? = null,
    val deletedAt: String? = null
)

/** Response envelope of GET /v2/apps/{appId}/messages/search. */
data class MessageSearchResponseDto(
    val items: List<RawMessageSearchHit>? = null,
    val total: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null
)

/**
 * Filters the raw page the way web does: a deleted message stays in the
 * archive with `deletedAt` set, and showing it would resurrect text its author
 * removed. nextOffset counts the server rows, not the rows kept.
 */
fun parseMessageSearchPage(
    dto: MessageSearchResponseDto?,
    requestedOffset: Int,
    requestedLimit: Int
): MessageSearchPage {
    val raw = dto?.items.orEmpty()
    val items = raw
        .filter { it.deletedAt.isNullOrEmpty() && !it.body.isNullOrEmpty() }
        .map { hit ->
            MessageSearchHit(
                id = hit._id.orEmpty(),
                chatId = hit.chatId.orEmpty(),
                chatType = hit.chatType.orEmpty(),
                room = hit.room.orEmpty(),
                from = hit.from.orEmpty(),
                fromUserId = hit.fromUserId.orEmpty(),
                body = hit.body.orEmpty(),
                messageId = hit.messageId.orEmpty(),
                stanzaId = hit.stanzaId.orEmpty(),
                createdAt = hit.createdAt.orEmpty()
            )
        }
    return MessageSearchPage(
        items = items,
        total = dto?.total ?: 0,
        offset = dto?.offset ?: requestedOffset,
        limit = dto?.limit ?: requestedLimit,
        nextOffset = requestedOffset + raw.size
    )
}

/**
 * A key unique per hit. stanzaId and messageId are NOT: on QA whole pages of
 * older hits came back with both empty, which made every key collide.
 */
fun MessageSearchHit.key(): String =
    id.ifEmpty { "$chatId:$stanzaId:$messageId:$createdAt:${body.take(24)}" }

/** Appends [next] to [current], skipping hits whose key is already there. */
fun mergeSearchHits(current: List<MessageSearchHit>, next: List<MessageSearchHit>): List<MessageSearchHit> {
    val seen = current.mapTo(HashSet()) { it.key() }
    return current + next.filter { seen.add(it.key()) }
}

private val DAY_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

private fun parseDay(day: String): LocalDate? {
    if (!DAY_PATTERN.matches(day)) return null
    return try {
        LocalDate.parse(day)
    } catch (_: Exception) {
        null
    }
}

/** `2026-07-14` -> that day's first instant in the reader's time zone. */
fun dayStartIso(day: String, zone: ZoneId = ZoneId.systemDefault()): String? =
    parseDay(day)?.atStartOfDay(zone)?.toInstant()?.toString()

/**
 * The day's LAST instant. The server reads a bare date in `until` as midnight
 * at the START of that day, which silently excluded the whole day picked.
 */
fun dayEndIso(day: String, zone: ZoneId = ZoneId.systemDefault()): String? =
    parseDay(day)?.atTime(23, 59, 59, 999_000_000)?.atZone(zone)?.toInstant()?.toString()

/** True when both days are set and the range runs backwards: no honest result. */
fun isBackwardsDateRange(from: String, to: String): Boolean =
    from.isNotEmpty() && to.isNotEmpty() && from > to

/** Piece of a snippet: [match] parts are the highlighted query. */
data class SnippetPart(val text: String, val match: Boolean)

private const val SNIPPET_CONTEXT_BEFORE = 36
private const val SNIPPET_MAX_LENGTH = 150

/**
 * The part of a message worth showing for a hit: a window around the first
 * match, cut on word boundaries with ellipses. Plain case-insensitive
 * substring; the query is never a regular expression.
 */
fun buildSnippet(body: String, query: String): List<SnippetPart> {
    val text = body.replace(Regex("\\s+"), " ").trim()
    val needle = query.trim().lowercase()
    if (text.isEmpty()) return emptyList()

    val at = if (needle.isNotEmpty()) text.lowercase().indexOf(needle) else -1

    var start = 0
    if (at > SNIPPET_CONTEXT_BEFORE) {
        start = at - SNIPPET_CONTEXT_BEFORE
        val space = text.indexOf(' ', start)
        if (space != -1 && space < at) start = space + 1
    }
    var end = minOf(text.length, start + SNIPPET_MAX_LENGTH)
    if (end < text.length) {
        val space = text.lastIndexOf(' ', end)
        if (space > start + SNIPPET_MAX_LENGTH / 2 && space > at + needle.length) end = space
    }

    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    val slice = text.substring(start, end)

    if (at == -1 || at < start || at + needle.length > end) {
        return listOf(SnippetPart("$prefix$slice$suffix", match = false))
    }

    val from = at - start
    val parts = mutableListOf<SnippetPart>()
    if (prefix.isNotEmpty() || from > 0) {
        parts.add(SnippetPart(prefix + slice.substring(0, from), match = false))
    }
    parts.add(SnippetPart(slice.substring(from, from + needle.length), match = true))
    val rest = slice.substring(from + needle.length)
    if (rest.isNotEmpty() || suffix.isNotEmpty()) {
        parts.add(SnippetPart(rest + suffix, match = false))
    }
    return parts
}

/** A person offered in the sender filter. */
data class PersonMatch(val id: String, val name: String)

/**
 * Up to [limit] people whose name contains [needle], names that START with it
 * first. [people] is (id, display name) pairs; big rooms have thousands of
 * members, so this never returns them all.
 */
fun matchPeople(people: Iterable<Pair<String, String>>, needle: String, limit: Int = 8): List<PersonMatch> {
    val wanted = needle.trim().lowercase()
    if (wanted.isEmpty()) return emptyList()
    val starts = mutableListOf<PersonMatch>()
    val contains = mutableListOf<PersonMatch>()
    val seen = HashSet<String>()
    for ((id, name) in people) {
        if (id.isBlank() || name.isBlank() || seen.contains(id)) continue
        val at = name.lowercase().indexOf(wanted)
        if (at == -1) continue
        seen.add(id)
        if (at == 0) starts.add(PersonMatch(id, name)) else contains.add(PersonMatch(id, name))
        if (starts.size >= limit) break
    }
    return (starts + contains).take(limit)
}

/** `appId_userId@xmpp.host` (or without the host) -> `appId_userId`. */
fun xmppLocalPart(jid: String?): String = (jid ?: "").substringBefore('@')

/** Who sent a search hit. */
sealed interface SearchSender {
    object Self : SearchSender
    data class Named(val name: String) : SearchSender
    object Unknown : SearchSender
}

/**
 * `from` carries the full JID, so looking it up as-is found nobody and every
 * hit was attributed to "Someone". The user id is the other way in.
 *  - [namesByUserId]: room members keyed by user id (`fromUserId`);
 *  - [namesByLocalPart]: known users keyed by the local part of their JID.
 */
fun resolveSearchSender(
    hit: MessageSearchHit,
    namesByUserId: Map<String, String>,
    namesByLocalPart: Map<String, String>,
    myXmppUsername: String?
): SearchSender {
    val from = xmppLocalPart(hit.from)
    val mine = xmppLocalPart(myXmppUsername)
    if (mine.isNotEmpty() && from == mine) return SearchSender.Self
    val name = namesByLocalPart[from]?.takeIf { it.isNotBlank() }
        ?: namesByUserId[hit.fromUserId]?.takeIf { it.isNotBlank() }
    return if (name != null) SearchSender.Named(name) else SearchSender.Unknown
}
