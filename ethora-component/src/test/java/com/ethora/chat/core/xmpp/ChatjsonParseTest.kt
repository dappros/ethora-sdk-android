package com.ethora.chat.core.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the private-store chatjson parser. The prosody mod
 * echoes the payload back with single-quoted attributes (and entity-escaped
 * content) — earlier versions only matched double quotes and silently
 * returned null, blocking the per-device unread baseline sync.
 */
class ChatjsonParseTest {

    @Test
    fun `parseChatjsonValue extracts double-quoted value attribute`() {
        // Content must be entity-escaped — the inner quotes can't be literal.
        val xml = """<chatjson xmlns="chatjson:store" value="{&quot;room@x&quot;:&quot;1700&quot;}"/>"""
        assertEquals("{\"room@x\":\"1700\"}", XMPPWebSocketConnection.parseChatjsonValue(xml))
    }

    @Test
    fun `parseChatjsonValue extracts single-quoted value attribute with entity-escaped JSON`() {
        // Mirrors what the dev2 prosody mod actually returns over the wire.
        val xml = """<iq type='result'><query xmlns='jabber:iq:private'>""" +
            """<chatjson xmlns='chatjson:store' value='{&quot;room@x&quot;:&quot;1700&quot;}'/>""" +
            """</query></iq>"""
        assertEquals("{\"room@x\":\"1700\"}", XMPPWebSocketConnection.parseChatjsonValue(xml))
    }

    @Test
    fun `parseChatjsonValue returns null when chatjson element is absent`() {
        val xml = """<iq type='result'><query xmlns='jabber:iq:private'/></iq>"""
        assertNull(XMPPWebSocketConnection.parseChatjsonValue(xml))
    }

    @Test
    fun `parseChatjsonValue returns empty string when value attribute is empty`() {
        val xml = """<chatjson xmlns='chatjson:store' value=''/>"""
        assertEquals("", XMPPWebSocketConnection.parseChatjsonValue(xml))
    }
}
