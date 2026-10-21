package com.ethora.chat.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the call stanza builders and parsers. Each stanza type
 * built by [CallStanzas] is parsed back to the same values.
 */
class CallStanzasTest {

    @Test
    fun `call-invite round trips kind room and callId`() {
        val data = CallStanzas.inviteData(CallKind.AUDIO, "app_room-1", "call-42")
        val stanza = CallStanzas.directMessage("bob@xmpp.example", "call-invite-1", data)

        val signal = CallStanzas.parseSignal(stanza) as CallStanzas.Signal.Invite
        assertEquals(CallKind.AUDIO, signal.kind)
        assertEquals("app_room-1", signal.room)
        assertEquals("call-42", signal.callId)
    }

    @Test
    fun `call-invite with no kind attribute defaults to video`() {
        val stanza = """<message><data type="call-invite" room="r1"/></message>"""
        val signal = CallStanzas.parseSignal(stanza) as CallStanzas.Signal.Invite
        assertEquals(CallKind.VIDEO, signal.kind)
    }

    @Test
    fun `call-state hangup round trips state room and callId`() {
        val data = CallStanzas.stateData("declined", "call-7", "app_room-9")
        val stanza = CallStanzas.directMessage("alice@xmpp.example", "call-state-1", data)

        val signal = CallStanzas.parseSignal(stanza) as CallStanzas.Signal.State
        assertEquals("declined", signal.state)
        assertEquals("app_room-9", signal.room)
        assertEquals("call-7", signal.callId)
        assertFalse("a bare hangup carries no log data", signal.hasLogData)
    }

    @Test
    fun `server call log carries caller and duration and has log data`() {
        val stanza = """<message from="room@conference.x/nick">
            <data type="call-state" callId="c9" durationMs="12000" callerXmppUsername="alice" kind="audio" isSystemMessage="true"/>
            </message>"""
        val signal = CallStanzas.parseSignal(stanza) as CallStanzas.Signal.State
        assertTrue(signal.hasLogData)
        assertEquals("alice", signal.callerXmppUsername)
        assertEquals(12000L, signal.durationMs)
        assertEquals("audio", signal.kind)
        assertEquals("", signal.state)
    }

    @Test
    fun `call-token parses token room kind and callId`() {
        val stanza = """<message><data type="call-token" token="abc.def.ghi" room="app_r1" kind="audio" callId="c3"/></message>"""
        val signal = CallStanzas.parseSignal(stanza) as CallStanzas.Signal.Token
        assertEquals("abc.def.ghi", signal.token)
        assertEquals("app_r1", signal.room)
        assertEquals("audio", signal.kind)
        assertEquals("c3", signal.callId)
    }

    @Test
    fun `unknown call type parses as Other and a plain message is not a signal`() {
        assertTrue(CallStanzas.parseSignal("""<message><data type="call-weird"/></message>""") is CallStanzas.Signal.Other)
        assertNull(CallStanzas.parseSignal("""<message><body>hello</body><data type="chat-text"/></message>"""))
    }

    @Test
    fun `attribute values are escaped on build and unescaped on parse`() {
        val data = CallStanzas.inviteData(CallKind.VIDEO, "a&b\"c", null)
        val signal = CallStanzas.parseSignal(CallStanzas.directMessage("p@h", "id1", data)) as CallStanzas.Signal.Invite
        assertEquals("a&b\"c", signal.room)
    }

    @Test
    fun `peerJid adds the host only when missing`() {
        assertEquals("bob@xmpp.example", CallStanzas.peerJid("bob", "xmpp.example"))
        assertEquals("bob@other.host", CallStanzas.peerJid("bob@other.host", "xmpp.example"))
    }

    @Test
    fun `call-token is fresh only within five seconds of its stanza-id timestamp`() {
        val now = 1_750_000_000_000L
        val fresh = """<message><stanza-id xmlns="urn:xmpp:sid:0" id="${now - 1000}"/><data type="call-token"/></message>"""
        val stale = """<message><stanza-id xmlns="urn:xmpp:sid:0" id="${now - 6000}"/><data type="call-token"/></message>"""
        assertTrue(CallStanzas.isFreshCallToken(fresh, now))
        assertFalse(CallStanzas.isFreshCallToken(stale, now))
        assertFalse("no stanza-id means not fresh", CallStanzas.isFreshCallToken("<message/>", now))
    }

    @Test
    fun `timestamp extraction keeps the first thirteen digits`() {
        assertEquals(1750000000123L, CallStanzas.extractNumericTimestamp("1750000000123"))
        assertEquals(1750000000123L, CallStanzas.extractNumericTimestamp("1750000000123456"))
        assertEquals(1750000000123L, CallStanzas.extractNumericTimestamp("send-1750000000123"))
        assertNull(CallStanzas.extractNumericTimestamp("abc"))
    }

    @Test
    fun `jwt claims give caller name and callId and reject raw ids`() {
        val payload = """{"sub":"Roman Test","metadata":"{\"callId\":\"call-xyz\"}"}"""
        val token = jwt(payload)
        val claims = CallStanzas.jwtPayload(token)
        assertEquals("Roman Test", CallStanzas.callerDisplayName(claims))
        assertEquals("call-xyz", CallStanzas.callIdFromJwt(claims))

        val rawId = CallStanzas.jwtPayload(jwt("""{"sub":"6a2c08adef26ca2d3e1e3677"}"""))
        assertEquals("", CallStanzas.callerDisplayName(rawId))
    }

    @Test
    fun `invalid jwt returns null`() {
        assertNull(CallStanzas.jwtPayload("not-a-jwt"))
        assertNull(CallStanzas.jwtPayload(""))
    }

    private fun jwt(payloadJson: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = encoder.encodeToString(payloadJson.toByteArray())
        return "$header.$body.sig"
    }
}
