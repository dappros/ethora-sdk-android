package com.ethora.chat.core.xmpp

// Shared helpers for the live (WebSocket) and history (MAM) parser paths.
// Centralised because the two paths had drifted apart on <data> handling
// and attribute decoding, which made file messages loaded from history
// render as plain text and broke signed-URL thumbnails.

// The backend stamps every broadcast / system message with this constant
// stanza id (send_system_message[_ws] / send_groupchat_message_ws in
// _xmpp.service.js). It is NOT a unique correlation handle, so it must never
// be used as a message id or matched during dedup — otherwise distinct
// broadcasts collapse onto one another.
internal const val BROADCAST_PLACEHOLDER_ID = "id"

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
    // Trigger on the pubsub#event envelope ALONE — do NOT additionally
    // require the literal `urn:xmpp:mucsub:nodes:messages` node string. The
    // web client (handleStanzas.unwrapMucsubMessage) unwraps purely by
    // structure (event → items → item → message) with no node check, and we
    // must match it: the forwarded copy doesn't always echo that exact node
    // attribute, and gating on it silently left those broadcasts wrapped.
    // The real discriminator is "does the item actually contain a <message>"
    // (handled below), so non-message pubsub events (PEP, presence/subject
    // payloads) still pass through untouched.
    if (!xml.contains("pubsub#event")) return xml
    // Find the <item> opening tag, tolerating BOTH `<item>` (no attributes —
    // the shape ejabberd emits when forwarding to the messages node, and the
    // exact shape this function's own docstring above shows) AND
    // `<item id='…'>`. The previous `indexOf("<item ")` required a trailing
    // space, so a bare `<item>` was never matched: the broadcast was left
    // wrapped and its id / sender / timestamp were then read off the pubsub
    // envelope instead of the inner stanza — the "broadcast messages not
    // always received" symptom. The pattern is anchored so it can never
    // match the enclosing `<items>` element.
    val itemMatch = Regex("<item(?:\\s[^>]*)?>").find(xml) ?: return xml
    val itemContentStart = itemMatch.range.last + 1
    val innerStart = xml.indexOf("<message", itemContentStart)
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
 * Extract the `id` attribute of a `<origin-id xmlns="urn:xmpp:sid:0" .../>`
 * element if present. This is the XEP-0359 sender-assigned id we attach to
 * every outgoing stanza — servers MUST forward it verbatim, so it survives
 * MUC-SUB pubsub wrapping, MAM re-serialisation, and `<message id>` rewrites
 * that some routers do. We use it as the primary correlation handle in the
 * pending-row reconciler, falling back to the stanza `id` attribute only
 * when origin-id is absent (legacy server / older sender).
 *
 * The regex is anchored on the `<origin-id` open tag so a `<stanza-id>` (a
 * different XEP-0359 element, server-assigned and namespaced the same way)
 * is not picked up here.
 */
internal fun extractOriginId(xml: String): String? {
    if (xml.isEmpty()) return null
    val match = Regex("<origin-id\\b[^>]*\\bid=['\"]([^'\"]+)['\"]").find(xml) ?: return null
    return decodeXmlEntities(match.groupValues[1])
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
