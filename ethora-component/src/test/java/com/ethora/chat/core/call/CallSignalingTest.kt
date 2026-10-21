package com.ethora.chat.core.call

import com.ethora.chat.core.config.VideoCallsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Inbound signalling router (port of web onCallTokenMessage). The config and
 * the clock are passed in, so the rules are checked without a live socket.
 */
class CallSignalingTest {

    private val now = 1_750_000_000_000L
    private val enabled = VideoCallsConfig(enabled = true, livekitUrl = "wss://lk.example")

    @Before
    fun reset() {
        CallStore.reset()
        CallSignaling.clearKindHints()
    }

    @Test
    fun `plain chat message is not call signalling`() {
        val msg = """<message type="groupchat"><body>hello, call-me later</body></message>"""
        assertFalse(CallSignaling.handleInbound(msg, now, enabled))
    }

    @Test
    fun `call-token is swallowed and ignored when calls are disabled`() {
        val stanza = tokenStanza(now, room = "unitroomoff@conf.test")
        assertTrue(CallSignaling.handleInbound(stanza, now, config = null))
        assertEquals(CallPhase.IDLE, CallStore.snapshot().phase)
    }

    @Test
    fun `stale call-token is swallowed and does not ring`() {
        val stanza = tokenStanza(now - 9_000, room = "unitroomstale@conf.test")
        assertTrue(CallSignaling.handleInbound(stanza, now, enabled))
        assertEquals(CallPhase.IDLE, CallStore.snapshot().phase)
    }

    @Test
    fun `fresh call-token rings with the caller name and the invite kind hint`() {
        val room = "unitroomring@conf.test"
        val invite = CallStanzas.directMessage(
            "me@host", "call-invite-1",
            CallStanzas.inviteData(CallKind.AUDIO, room, callId = null)
        )
        assertTrue(CallSignaling.handleInbound(invite, now, enabled))

        assertTrue(CallSignaling.handleInbound(tokenStanza(now, room = room), now, enabled))
        val s = CallStore.snapshot()
        assertEquals(CallPhase.RINGING_INCOMING, s.phase)
        assertEquals(CallKind.AUDIO, s.kind)
        assertEquals(room, s.roomJid)
        assertEquals("Roman Test", s.roomName)
        assertEquals("call-jwt-1", s.callId)
    }

    @Test
    fun `invite for the active call switches its kind`() {
        CallStore.startOutgoingCall("unitroomkind@conf.test", "Bob", "unitroomkind", CallKind.VIDEO, "bob", now)
        val invite = CallStanzas.directMessage(
            "me@host", "call-invite-2",
            CallStanzas.inviteData(CallKind.AUDIO, "unitroomkind", callId = null)
        )
        assertTrue(CallSignaling.handleInbound(invite, now, enabled))
        assertEquals(CallKind.AUDIO, CallStore.snapshot().kind)
    }

    @Test
    fun `terminal call-state for the active room tears the call down`() {
        CallStore.startOutgoingCall("unitroomend@conf.test", "Bob", "unitroomend", CallKind.VIDEO, "bob", now)
        val cancel = CallStanzas.directMessage(
            "me@host", "call-state-9",
            CallStanzas.stateData("cancelled", callId = null, room = "unitroomend")
        )
        assertTrue(CallSignaling.handleInbound(cancel, now, enabled))
        assertEquals(CallPhase.IDLE, CallStore.snapshot().phase)
    }

    @Test
    fun `call-state for another room does not tear the call down`() {
        CallStore.startOutgoingCall("unitroomkeep@conf.test", "Bob", "unitroomkeep", CallKind.VIDEO, "bob", now)
        val other = CallStanzas.directMessage(
            "me@host", "call-state-10",
            CallStanzas.stateData("ended", callId = null, room = "someoneelse")
        )
        assertTrue(CallSignaling.handleInbound(other, now, enabled))
        assertEquals(CallPhase.REQUESTING, CallStore.snapshot().phase)
    }

    @Test
    fun `body-only call keyword is swallowed and never shown as chat`() {
        val stanza = """<message type="chat"><body>call-ended</body></message>"""
        assertTrue(CallSignaling.handleInbound(stanza, now, enabled))
    }

    @Test
    fun `kind hint expires after its ttl`() {
        CallSignaling.stashKindHint("hintroom", CallKind.AUDIO, now = 0L)
        assertNull(CallSignaling.consumeKindHint(listOf("hintroom"), now = 70_000L))
        CallSignaling.stashKindHint("hintroom2", CallKind.AUDIO, now = 0L)
        assertEquals(CallKind.AUDIO, CallSignaling.consumeKindHint(listOf("hintroom2"), now = 1_000L))
    }

    @Test
    fun `peer is the room part that is not us`() {
        assertEquals("bob", CallSignaling.derivePeerXmppUsername("alice-bob", "alice@xmpp.example"))
        assertEquals("alice", CallSignaling.derivePeerXmppUsername("alice-bob", "bob"))
        assertNull(CallSignaling.derivePeerXmppUsername("app_room123", "alice"))
    }

    private fun tokenStanza(stampMs: Long, room: String): String {
        val token = jwt("""{"sub":"Roman Test","metadata":"{\"callId\":\"call-jwt-1\"}"}""")
        return """<message type="chat"><stanza-id xmlns="urn:xmpp:sid:0" id="$stampMs" by="x"/><data type="call-token" token="$token" room="$room" kind="video"/></message>"""
    }

    private fun jwt(payloadJson: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = encoder.encodeToString(payloadJson.toByteArray())
        return "$header.$body.sig"
    }
}
