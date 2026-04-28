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

private fun decodeXmlEntities(value: String): String =
    value.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
