package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message

/**
 * Paging cursor rules for MAM history. Port of the web rules in
 * xmppClient.ts (routeMamStanza, mamCursor tests) and useJumpToMessage.ts.
 *
 * The cursor is ALWAYS a server archive id (microseconds). A millisecond
 * timestamp sent as `<before>` is read by the server as a much older id, which
 * returns the first page of the archive ("skip middle, jump to 1-9").
 */

private val ARCHIVE_ID = Regex("^\\d{13,}$")

/** Pages without progress in a row before the history counts as exhausted. */
const val MAX_HISTORY_PAGE_FAILURES = 3

/** The archive id of a message, or null (optimistic and pending ids have none). */
fun archiveCursorOf(message: Message): Long? {
    for (candidate in listOf(message.archiveId, message.id)) {
        if (candidate != null && ARCHIVE_ID.matches(candidate)) return candidate.toLongOrNull()
    }
    return null
}

/** The oldest archive id among [messages], i.e. where an older page must start. */
fun oldestArchiveCursor(messages: List<Message>): Long? =
    messages.mapNotNull { archiveCursorOf(it) }.minOrNull()

/** What the next older page starts from. */
data class PagingState(
    /** The archive id the next page must come before. */
    val cursor: Long,
    /** No further page can be asked for. */
    val exhausted: Boolean
)

/**
 * The state after an older-page request that began before [before].
 *  - a page that does not move the cursor backwards is not progress; asking
 *    again with the same cursor would repeat it forever, so it is exhausted;
 *  - the server's <fin complete> means there is nothing older at all.
 * A failed request leaves the cursor where it was (the caller counts failures).
 */
fun advanceOlderCursor(before: Long, ok: Boolean, complete: Boolean, first: Long?): PagingState {
    if (!ok) return PagingState(cursor = before, exhausted = false)
    val progressed = first != null && first < before
    val cursor = if (progressed) first!! else before
    return PagingState(cursor = cursor, exhausted = complete || !progressed)
}

/** Same rule for a window's newer side: the cursor only moves forwards. */
fun advanceNewerCursor(after: Long, ok: Boolean, complete: Boolean, last: Long?): PagingState {
    if (!ok) return PagingState(cursor = after, exhausted = false)
    val progressed = last != null && last > after
    val cursor = if (progressed) last!! else after
    return PagingState(cursor = cursor, exhausted = complete || !progressed)
}
