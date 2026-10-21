package com.ethora.chat.core.xmpp

/**
 * Stanza builders and parsers for delivery receipts (XEP-0184), read markers
 * (XEP-0333) and typing notifications (chat states).
 *
 * Wire-compatible with web: networking/xmpp/sendReceivedReceipt.xmpp.ts,
 * sendDisplayedMarker.xmpp.ts, sendTypingRequest.xmpp.ts and the handlers in
 * networking/stanzaHandlers.ts (onReceivedReceipt, onDisplayedMarker).
 */
object ReceiptStanzas {
    const val RECEIPTS_NS = "urn:xmpp:receipts"
    const val CHAT_MARKERS_NS = "urn:xmpp:chat-markers:0"
    const val HINTS_NS = "urn:xmpp:hints"
    const val CHATSTATES_NS = "http://jabber.org/protocol/chatstates"

    /** Stanza id prefixes web uses for these stanzas. */
    const val RECEIVED_ID_PREFIX = "message-received:"
    const val DISPLAYED_ID_PREFIX = "message-displayed:"

    /**
     * A parsed receipt or displayed marker. [fromXmppUsername] is the local
     * part of the real sender (the `from` attribute on the inner element), not
     * the room nickname the MUC rewrote onto the outer stanza.
     */
    data class Receipt(
        val messageId: String,
        val fromXmppUsername: String,
        val roomJid: String
    )

    /** XEP-0184 delivery receipt, broadcast to the room. [from] is the bare sender JID. */
    fun receivedStanza(id: String, from: String, roomJid: String, messageId: String): String =
        "<message id=\"${escape(id)}\" type=\"groupchat\" from=\"${escape(from)}\" to=\"${escape(roomJid)}\">" +
            "<received xmlns=\"$RECEIPTS_NS\" id=\"${escape(messageId)}\" from=\"${escape(from)}\"/>" +
            "<store xmlns=\"$HINTS_NS\"/>" +
            "</message>"

    /** XEP-0333 read marker: [messageId] is the newest message the sender has displayed. */
    fun displayedStanza(id: String, from: String, roomJid: String, messageId: String): String =
        "<message id=\"${escape(id)}\" type=\"groupchat\" from=\"${escape(from)}\" to=\"${escape(roomJid)}\">" +
            "<displayed xmlns=\"$CHAT_MARKERS_NS\" id=\"${escape(messageId)}\" from=\"${escape(from)}\"/>" +
            "<store xmlns=\"$HINTS_NS\"/>" +
            "</message>"

    /** Chat-state notification (composing or paused) with the sender's display name. */
    fun typingStanza(id: String, roomJid: String, fullName: String, composing: Boolean): String {
        val state = if (composing) "composing" else "paused"
        return "<message type=\"groupchat\" id=\"${escape(id)}\" to=\"${escape(roomJid)}\">" +
            "<$state xmlns=\"$CHATSTATES_NS\"/>" +
            "<data fullName=\"${escape(fullName)}\"/>" +
            "</message>"
    }

    /** Parses a `<received>` receipt from a reflected (MUC-rewritten) stanza. Null if absent. */
    fun parseReceived(xml: String): Receipt? = parseReceiptElement(xml, "received", RECEIPTS_NS)

    /** Parses a `<displayed>` read marker from a reflected (MUC-rewritten) stanza. Null if absent. */
    fun parseDisplayed(xml: String): Receipt? = parseReceiptElement(xml, "displayed", CHAT_MARKERS_NS)

    /** Bare local part of a JID, normalised the way the composing guard does (no domain, no `_`, lowercase). */
    fun normalizeLocal(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().substringBefore("/").substringBefore("@")
            .replace("_", "")
            .lowercase()
    }

    private fun parseReceiptElement(xml: String, element: String, namespace: String): Receipt? {
        val roomJid = Regex("<message\\b[^>]*?\\sfrom=[\"']([^\"']+)[\"']")
            .find(xml)?.groupValues?.get(1)?.substringBefore("/")
            ?.takeIf { it.isNotBlank() } ?: return null
        for (match in Regex("<$element\\b([^>]*)>").findAll(xml)) {
            val attrs = match.groupValues[1]
            if (!attrs.contains(namespace)) continue
            val messageId = attribute(attrs, "id")?.takeIf { it.isNotBlank() } ?: return null
            val from = attribute(attrs, "from")?.takeIf { it.isNotBlank() } ?: return null
            val fromLocal = from.substringBefore("/").substringBefore("@")
            if (fromLocal.isBlank()) return null
            return Receipt(messageId = messageId, fromXmppUsername = fromLocal, roomJid = roomJid)
        }
        return null
    }

    private fun attribute(attrs: String, name: String): String? {
        val match = Regex("\\s$name=([\"'])(.*?)\\1").find(" $attrs") ?: return null
        return unescape(match.groupValues[2])
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
