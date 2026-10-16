package com.ethora.chat.core.xmpp

/**
 * One `<reactions xmlns="urn:xmpp:reactions:0">` stanza, live or from MAM.
 *
 * Mirrors web `stanzaHandlers.ts` (`onReactionMessage`, `onReactionHistory`) and
 * `helpers/mamReactions.ts` (`extractMamReaction`):
 *  - the reacted message is `<reactions id>` (its archive id),
 *  - the reactor is the `<reactions from>` JID, keyed by its localpart,
 *  - the room is `<stanza-id by>`, NOT the `to` attribute (which is the
 *    recipient occupant on live MUC reflections),
 *  - an empty emoji list is a removal marker for that reactor.
 */
data class ParsedReactionStanza(
    val targetId: String,
    val from: String,
    val reactorId: String,
    val emoji: List<String>,
    val data: Map<String, String>,
    val roomJid: String?,
    /** Numeric value of `<stanza-id id>` (archive microseconds), 0 when absent. */
    val timestamp: Long
)

/**
 * Pure XML helpers for reaction stanzas. No Android types, so they run in
 * plain JVM unit tests.
 */
object ReactionStanza {
    const val NAMESPACE = "urn:xmpp:reactions:0"

    private val reactionsStartTag = Regex("<(?:[A-Za-z0-9_-]+:)?reactions(?=[\\s/>])[^>]*>")
    private val reactionsEndTag = Regex("</(?:[A-Za-z0-9_-]+:)?reactions\\s*>")
    private val reactionChild = Regex("<reaction(?:\\s[^>]*)?>([^<]*)</reaction>")
    private val dataStartTag = Regex("<data(?=[\\s/>])[^>]*>")
    private val stanzaIdStartTag = Regex("<stanza-id(?=[\\s/>])[^>]*>")
    private val messageStartTag = Regex("<message(?=[\\s/>])[^>]*>")
    private val attributePattern = Regex("([A-Za-z_][\\w:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /**
     * Parses the first reaction stanza in [xml]. Returns null when there is no
     * `<reactions>` element or it has no target id or reactor.
     */
    fun parse(xml: String): ParsedReactionStanza? {
        val startMatch = reactionsStartTag.find(xml) ?: return null
        val startTag = startMatch.value
        val attrs = parseAttributes(startTag)
        val targetId = attrs["id"]?.takeIf { it.isNotBlank() } ?: return null
        val from = attrs["from"]?.takeIf { it.isNotBlank() } ?: return null
        val reactorId = from.substringBefore('@').takeIf { it.isNotBlank() } ?: return null

        val emoji = if (startTag.endsWith("/>")) {
            emptyList()
        } else {
            val closeMatch = reactionsEndTag.find(xml, startMatch.range.last + 1)
            val inner = xml.substring(
                startMatch.range.last + 1,
                closeMatch?.range?.first ?: xml.length
            )
            reactionChild.findAll(inner)
                .map { decodeXml(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .toList()
        }

        val data = dataStartTag.find(xml)?.value?.let { parseAttributes(it) }
            ?.filterKeys { it == "senderFirstName" || it == "senderLastName" }
            ?: emptyMap()

        val stanzaId = stanzaIdStartTag.find(xml)?.value?.let { parseAttributes(it) }
        val by = stanzaId?.get("by")?.takeIf { it.isNotBlank() }
        val roomJid = by ?: messageStartTag.find(xml)?.value
            ?.let { parseAttributes(it)["from"] }
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
        val timestamp = stanzaId?.get("id")?.toLongOrNull() ?: 0L

        return ParsedReactionStanza(
            targetId = targetId,
            from = from,
            reactorId = reactorId,
            emoji = emoji,
            data = data,
            roomJid = roomJid,
            timestamp = timestamp
        )
    }

    /**
     * Builds the groupchat stanza that sets MY full reaction list on one message.
     * Same shape as web `sendMessageReaction.xmpp.ts`: an empty [emoji] list is a
     * "remove all" marker, and a toggle is a resend of the whole list.
     */
    fun buildSend(
        stanzaId: String,
        fromJid: String,
        roomJid: String,
        targetId: String,
        emoji: List<String>,
        firstName: String,
        lastName: String
    ): String {
        val reactionXml = emoji.joinToString("") { "<reaction>${escapeXml(it)}</reaction>" }
        val escapedFrom = escapeXml(fromJid)
        return "<message type=\"groupchat\" id=\"${escapeXml(stanzaId)}\" from=\"$escapedFrom\" to=\"${escapeXml(roomJid)}\">" +
            "<reactions id=\"${escapeXml(targetId)}\" from=\"$escapedFrom\" xmlns=\"$NAMESPACE\">$reactionXml</reactions>" +
            "<data senderFirstName=\"${escapeXml(firstName)}\" senderLastName=\"${escapeXml(lastName)}\"/>" +
            "<store xmlns=\"urn:xmpp:hints\"/>" +
            "</message>"
    }

    /** Stanza id for a reaction send, matching web `message-reaction:{ms}`. */
    fun newStanzaId(nowMs: Long = System.currentTimeMillis()): String = "message-reaction:$nowMs"

    /** Attributes of one start tag, entity-decoded. */
    fun parseAttributes(startTag: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (match in attributePattern.findAll(startTag)) {
            val name = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[name] = decodeXml(value)
        }
        return result
    }

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun decodeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
