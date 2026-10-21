package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.math.abs

/**
 * Locating a message that is not in the loaded history. Port of web
 * helpers/jumpWindow.ts and the matcher in useJumpToMessage.ts.
 *
 * A window is fetched from the archive (before and after the target) and kept
 * apart from the live list, so a far target does not mean loading everything
 * between it and the newest message.
 */

/** Messages shown above and below the target when a window opens. */
const val JUMP_WINDOW_BEFORE = 10
const val JUMP_WINDOW_AFTER = 10
/** Page size when the reader scrolls toward either edge of a window. */
const val JUMP_WINDOW_PAGE = 20

/** How far apart the archive's timestamp and the transcript's may be for the same message. */
const val CONTENT_MATCH_WINDOW_MS = 5_000L
private const val TIME_LOOKUP_PAGE = 50
private const val TIME_LOOKUP_MAX_PAGES = 4

/** What a request knows about its message besides ids: the text and the time. */
data class JumpContent(val createdAt: String?, val body: String?)

/** A window of archive messages around a target, ascending. */
data class JumpWindow(
    val roomJid: String,
    val messages: List<Message>,
    val targetId: String,
    val olderCursor: Long?,
    val hasOlder: Boolean,
    val newerCursor: Long?,
    val hasNewer: Boolean
)

sealed interface JumpWindowResult {
    /** The window is ready to be shown. */
    data class Found(val window: JumpWindow) : JumpWindowResult
    /** The server answered and the target is not in its archive. */
    object Missing : JumpWindowResult
    /** No usable id, or a request failed: the caller falls back to paging. */
    object Unavailable : JumpWindowResult
}

private val MAM_ID = Regex("^\\d{13,}$")

/** The ids that can be archive ids (microsecond timestamps), without duplicates. */
fun mamIdCandidates(ids: List<String>): List<String> =
    ids.map { it.trim() }.filter { MAM_ID.matches(it) }.distinct()

/** Parses an ISO 8601 instant to epoch millis, or null. */
internal fun parseInstantMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value.trim()).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/**
 * Index of the message a jump names. By id first (message.id is the archive
 * id for history, xmppId the client message id). Without a usable id, by
 * content: the same text, sent within a few seconds of the same time.
 */
fun findMessageIndex(messages: List<Message>, ids: List<String>, content: JumpContent? = null): Int {
    if (ids.isNotEmpty()) {
        val byId = messages.indexOfFirst { message ->
            ids.contains(message.id) || (message.xmppId?.let { ids.contains(it) } ?: false)
        }
        if (byId >= 0) return byId
    }
    val wanted = parseInstantMs(content?.createdAt) ?: return -1
    val body = content?.body?.trim()?.takeIf { it.isNotEmpty() } ?: return -1
    return messages.indexOfFirst { message ->
        message.body.trim() == body && abs(message.date.time - wanted) <= CONTENT_MATCH_WINDOW_MS
    }
}

/**
 * Fetches about [JUMP_WINDOW_BEFORE] messages before the target, the target
 * and about [JUMP_WINDOW_AFTER] after it: two MAM queries in parallel. The
 * target itself comes with the "after" page, asked from one microsecond before.
 */
suspend fun loadJumpWindow(
    client: HistoryWindowClient,
    roomJid: String,
    ids: List<String>,
    content: JumpContent? = null
): JumpWindowResult {
    val candidates = mamIdCandidates(ids)
    if (candidates.isEmpty()) {
        return if (content?.createdAt != null && !content.body.isNullOrBlank()) {
            loadJumpWindowByTime(client, roomJid, content)
        } else {
            JumpWindowResult.Unavailable
        }
    }

    for (candidate in candidates) {
        val id = candidate.toLong()
        val (older, newer) = coroutineScope {
            val olderDeferred = async {
                client.getHistoryWindow(roomJid, JUMP_WINDOW_BEFORE, WindowCursor(before = id))
            }
            val newerDeferred = async {
                client.getHistoryWindow(roomJid, JUMP_WINDOW_AFTER + 1, WindowCursor(after = id - 1))
            }
            olderDeferred.await() to newerDeferred.await()
        }
        if (!older.ok || !newer.ok) return JumpWindowResult.Unavailable

        val target = newer.messages.firstOrNull { it.id == candidate } ?: continue
        val merged = (older.messages + newer.messages).distinctBy { it.id }
        return JumpWindowResult.Found(
            JumpWindow(
                roomJid = roomJid,
                messages = merged,
                targetId = target.id,
                olderCursor = older.first ?: id,
                hasOlder = !older.complete && older.first != null,
                newerCursor = newer.last ?: id,
                hasNewer = !newer.complete && newer.last != null
            )
        )
    }

    // None of the ids was an archive id after all. A content match can still find it.
    if (content?.createdAt != null && !content.body.isNullOrBlank()) {
        return loadJumpWindowByTime(client, roomJid, content)
    }
    return JumpWindowResult.Missing
}

/**
 * For a message with no usable archive id: ask for the rows within a few
 * seconds of its time (MAM start/end filter), find the one with the same text,
 * and continue as the id-based window does with that row's id.
 */
suspend fun loadJumpWindowByTime(
    client: HistoryWindowClient,
    roomJid: String,
    content: JumpContent
): JumpWindowResult {
    val wanted = parseInstantMs(content.createdAt) ?: return JumpWindowResult.Unavailable
    val body = content.body?.trim().orEmpty()
    if (body.isEmpty()) return JumpWindowResult.Unavailable

    val start = Instant.ofEpochMilli(wanted - CONTENT_MATCH_WINDOW_MS).toString()
    val end = Instant.ofEpochMilli(wanted + CONTENT_MATCH_WINDOW_MS).toString()

    var after: Long? = null
    var best: Message? = null
    for (page in 0 until TIME_LOOKUP_MAX_PAGES) {
        val result = client.getHistoryWindow(
            roomJid,
            TIME_LOOKUP_PAGE,
            WindowCursor(after = after, start = start, end = end)
        )
        if (!result.ok) return JumpWindowResult.Unavailable
        for (message in result.messages) {
            if (message.body.trim() != body) continue
            val gap = abs(message.date.time - wanted)
            if (gap > CONTENT_MATCH_WINDOW_MS) continue
            val bestGap = best?.let { abs(it.date.time - wanted) }
            if (best == null || gap < bestGap!!) best = message
        }
        if (best != null) break
        if (result.complete || result.last == null) return JumpWindowResult.Missing
        after = result.last
    }

    val found = best ?: return JumpWindowResult.Unavailable
    if (!MAM_ID.matches(found.id)) return JumpWindowResult.Unavailable
    return loadJumpWindow(client, roomJid, listOf(found.id), null)
}

/** The page above a window, or null when the request failed. */
suspend fun loadOlderWindowPage(client: HistoryWindowClient, window: JumpWindow): OlderWindowPage? {
    val cursor = window.olderCursor ?: return OlderWindowPage(emptyList(), null, false)
    val page = client.getHistoryWindow(window.roomJid, JUMP_WINDOW_PAGE, WindowCursor(before = cursor))
    if (!page.ok) return null
    val state = advanceOlderCursor(cursor, ok = true, complete = page.complete, first = page.first)
    return OlderWindowPage(page.messages, state.cursor, hasOlder = !state.exhausted)
}

/** The page below a window, or null when the request failed. */
suspend fun loadNewerWindowPage(client: HistoryWindowClient, window: JumpWindow): NewerWindowPage? {
    val cursor = window.newerCursor ?: return NewerWindowPage(emptyList(), null, false)
    val page = client.getHistoryWindow(window.roomJid, JUMP_WINDOW_PAGE, WindowCursor(after = cursor))
    if (!page.ok) return null
    val state = advanceNewerCursor(cursor, ok = true, complete = page.complete, last = page.last)
    return NewerWindowPage(page.messages, state.cursor, hasNewer = !state.exhausted)
}

/**
 * Adds a freshly loaded [incoming] page to a window's [current] messages: messages
 * already shown stay as they are, the rest join and the list stays ascending by time.
 */
fun mergeWindowMessages(current: List<Message>, incoming: List<Message>): List<Message> {
    val seen = current.mapTo(HashSet()) { it.id }
    val added = incoming.filter { seen.add(it.id) }
    if (added.isEmpty()) return current
    return (current + added).sortedBy { it.timestamp ?: it.date.time }
}

data class OlderWindowPage(val messages: List<Message>, val olderCursor: Long?, val hasOlder: Boolean)
data class NewerWindowPage(val messages: List<Message>, val newerCursor: Long?, val hasNewer: Boolean)

/**
 * The message a reply quotes (`mainMessage`), or null. Clients write it either
 * as the bare id (this SDK) or as JSON `{"id": ...}` (web createMainMessageForThread).
 */
fun replyParentIdOf(message: Message): String? {
    if (message.isReply != true) return null
    val raw = message.mainMessage?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (!raw.startsWith("{")) return raw
    return try {
        com.google.gson.JsonParser.parseString(raw).asJsonObject
            .get("id")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
