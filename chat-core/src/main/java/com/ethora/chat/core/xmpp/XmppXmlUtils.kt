package com.ethora.chat.core.xmpp

// Shared helpers for the live (WebSocket) and history (MAM) parser paths.
// Centralised because the two paths had drifted apart on <data> handling
// and attribute decoding, which made file messages loaded from history
// render as plain text and broke signed-URL thumbnails.

internal fun extractDataElement(xml: String): String {
    val start = xml.indexOf("<data")
    if (start == -1) return ""
    val openTagEnd = xml.indexOf('>', start)
    if (openTagEnd == -1) return ""
    if (xml.getOrNull(openTagEnd - 1) == '/') {
        return xml.substring(start, openTagEnd + 1)
    }
    val close = xml.indexOf("</data>", openTagEnd)
    if (close == -1) return ""
    return xml.substring(start, close + "</data>".length)
}

internal fun extractAttribute(xml: String, attr: String): String? {
    if (xml.isEmpty()) return null
    "$attr=\"([^\"]+)\"".toRegex().find(xml)?.let { return decodeXmlEntities(it.groupValues[1]) }
    "$attr='([^']+)'".toRegex().find(xml)?.let { return decodeXmlEntities(it.groupValues[1]) }
    "$attr=([^\\s>]+)".toRegex().find(xml)?.let { return decodeXmlEntities(it.groupValues[1]) }
    return null
}

/**
 * If [xml] is an outer `<message>` that wraps a MUC-SUB pubsub event
 * (`<event xmlns='http://jabber.org/protocol/pubsub#event'><items
 *  node='urn:xmpp:mucsub:nodes:messages'><item><message ...>...real
 * stanza...</message></item></items></event>`), return JUST the inner
 * `<message>...</message>`. Otherwise return [xml] unchanged.
 *
 * Why this matters: the inner stanza's `id` is the `send-text-message:UUID`
 * we set as `customId` when we transmitted the message. The OUTER wrapper
 * id is a server-side pubsub sequence number. If the realtime parser reads
 * `id` off the outer envelope, optimistic-row dedup against our local
 * pending message fails (incoming.id != existing.id), and every echo
 * paints a second visible bubble — the "spam-send doubles every message"
 * bug. Unwrap first.
 */
internal fun unwrapMucSubInnerMessage(xml: String): String {
    if (xml.isEmpty()) return xml
    if (!xml.contains("pubsub#event") || !xml.contains("urn:xmpp:mucsub:nodes:messages")) return xml
    val itemOpen = xml.indexOf("<item ")
    val itemContentStart = if (itemOpen == -1) -1 else xml.indexOf('>', itemOpen)
    if (itemContentStart == -1) return xml
    val innerStart = xml.indexOf("<message", itemContentStart + 1)
    if (innerStart == -1) return xml
    // Find the matching </message> at the same depth — a forwarded chat
    // message can contain nested <message> elements (replies, reactions
    // on some servers), so we track depth instead of grabbing the first
    // </message>.
    var depth = 0
    var i = innerStart
    while (i < xml.length) {
        val open = xml.indexOf("<message", i)
        val close = xml.indexOf("</message>", i)
        if (close == -1) return xml
        if (open != -1 && open < close) {
            depth++
            i = open + "<message".length
        } else {
            depth--
            if (depth == 0) {
                val end = close + "</message>".length
                return xml.substring(innerStart, end)
            }
            i = close + "</message>".length
        }
    }
    return xml
}

/**
 * Extract the textual contents of the first `<body>...</body>` element.
 *
 * Tolerates every body shape ejabberd / Smack hands back through MAM:
 *   `<body>hello</body>`
 *   `<body xml:lang="en">hello</body>`
 *   `<body  xmlns="jabber:client">hello</body>`
 *
 * Returns `null` when no body tag is present (caller decides whether that
 * is a drop-worthy stanza or a presence/chat-state). XML entities inside
 * the body are decoded.
 *
 * The old MAM parser used `indexOf("<body>")`, which silently dropped
 * every archived stanza whose `<body>` opening tag carried an attribute —
 * a frequent cause of "some messages omitted" when scrolling 1-1 chat
 * history because ejabberd re-serialises archived stanzas with namespace
 * attributes on the body.
 */
internal fun extractBody(xml: String): String? {
    val match = Regex("<body\\b[^>]*>([\\s\\S]*?)</body>").find(xml) ?: return null
    return decodeXmlEntities(match.groupValues[1])
}

private fun decodeXmlEntities(value: String): String =
    value.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
