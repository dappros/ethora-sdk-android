package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message

/**
 * MAM (XEP-0313) query building and <fin> parsing for paged and windowed
 * history. Mirrors web xmppClient.ts requestMamHistory: same element order,
 * RSM `after` / `before`, and the optional `start`/`end` data form.
 */

/** Query ids of windowed requests start with this, so their <fin> does not touch the room's live history cursor. */
const val MAM_WINDOW_QUERY_PREFIX = "mam-window:"

/**
 * Where a MAM page starts. `before`/`after` are exclusive archive ids (microseconds).
 * `start`/`end` are ISO 8601 bounds of a time filter; with them and no
 * before/after the server answers with the first page of that range, oldest first.
 */
data class WindowCursor(
    val before: Long? = null,
    val after: Long? = null,
    val start: String? = null,
    val end: String? = null
)

/** What the server's <fin> said about one page. */
data class HistoryWindowFin(
    val complete: Boolean,
    /** RSM `<first>`: archive id of the oldest ROW returned (not the oldest displayed message). */
    val first: Long?,
    /** RSM `<last>`: archive id of the newest ROW returned. */
    val last: Long?
)

/** One page of a windowed history query, returned to the caller and never stored. */
data class HistoryWindowPage(
    val ok: Boolean,
    /** Ascending by time (oldest first), whichever direction was paged. */
    val messages: List<Message>,
    val complete: Boolean,
    val first: Long?,
    val last: Long?
) {
    companion object {
        val FAILED = HistoryWindowPage(ok = false, messages = emptyList(), complete = false, first = null, last = null)
    }
}

/** The slice of the XMPP client the jump-window helpers need. */
interface HistoryWindowClient {
    suspend fun getHistoryWindow(roomJid: String, max: Int, cursor: WindowCursor): HistoryWindowPage
}

/**
 * The MAM query IQ. Same shape as web's serialiser output: compact, double
 * quotes, no whitespace text nodes between elements.
 */
fun buildMamQueryStanza(roomJid: String, queryId: String, max: Int, cursor: WindowCursor): String {
    val hasRange = cursor.start != null || cursor.end != null
    val form = if (hasRange) {
        buildString {
            append("<x xmlns=\"jabber:x:data\" type=\"submit\">")
            append("<field var=\"FORM_TYPE\" type=\"hidden\"><value>urn:xmpp:mam:2</value></field>")
            cursor.start?.let { append("<field var=\"start\"><value>${escapeMamXml(it)}</value></field>") }
            cursor.end?.let { append("<field var=\"end\"><value>${escapeMamXml(it)}</value></field>") }
            append("</x>")
        }
    } else {
        ""
    }
    val rsm = when {
        cursor.after != null -> "<after>${cursor.after}</after>"
        cursor.before != null -> "<before>${cursor.before}</before>"
        hasRange -> ""
        else -> "<before/>"
    }
    return "<iq type=\"set\" to=\"${escapeMamXml(roomJid)}\" id=\"${escapeMamXml(queryId)}\">" +
        "<query xmlns=\"urn:xmpp:mam:2\" queryid=\"${escapeMamXml(queryId)}\">" +
        form +
        "<set xmlns=\"http://jabber.org/protocol/rsm\"><max>$max</max>$rsm</set>" +
        "</query></iq>"
}

/** Reads the <fin> of a MAM result IQ, or null when the stanza has none. */
fun parseMamFin(xml: String): HistoryWindowFin? {
    val start = Regex("<fin[\\s>/]").find(xml)?.range?.first ?: return null
    val close = xml.indexOf("</fin>", start)
    val fin = if (close == -1) xml.substring(start) else xml.substring(start, close)
    val openTag = fin.substringBefore('>')
    val complete = Regex("\\bcomplete\\s*=\\s*['\"]true['\"]").containsMatchIn(openTag)
    val first = Regex("<first\\b[^>]*>\\s*(\\d+)\\s*</first>").find(fin)?.groupValues?.get(1)?.toLongOrNull()
    val last = Regex("<last\\b[^>]*>\\s*(\\d+)\\s*</last>").find(fin)?.groupValues?.get(1)?.toLongOrNull()
    return HistoryWindowFin(complete = complete, first = first, last = last)
}

internal fun escapeMamXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

/** One page of live history (getHistory): what the server said about it, next to the messages. */
data class HistoryPage(
    /** False when the query timed out or could not be sent: no cursor progress can be trusted. */
    val ok: Boolean,
    /** Ascending by time, oldest first. Stored by the time they arrive, as before. */
    val messages: List<Message>,
    /** The server's <fin complete>: there is nothing older. */
    val complete: Boolean,
    /** RSM `<first>` of the page: the cursor for the next older page. */
    val first: Long?
) {
    companion object {
        val FAILED = HistoryPage(ok = false, messages = emptyList(), complete = false, first = null)
    }
}
