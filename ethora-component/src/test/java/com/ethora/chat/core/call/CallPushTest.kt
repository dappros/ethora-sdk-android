package com.ethora.chat.core.call

import com.ethora.chat.core.config.VideoCallsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallPushTest {

    private val enabled = VideoCallsConfig(enabled = true, livekitUrl = "wss://lk.example")

    @Before
    fun reset() {
        CallStore.reset()
    }

    @Test
    fun `a call push is told apart from a chat push`() {
        assertFalse(CallPush.isCallPush(mapOf("jid" to "room@conf", "body" to "hi")))
        assertTrue(CallPush.isCallPush(mapOf("type" to "call")))
        assertTrue(CallPush.isCallPush(mapOf("callId" to "c1", "kind" to "audio")))
        assertTrue("a p2p push has an empty token but names its mode", CallPush.isCallPush(mapOf("callId" to "c1", "callToken" to "", "mode" to "p2p")))
        assertFalse(CallPush.isCallPush(null))
    }

    @Test
    fun `fields are read tolerantly and the caller name is sanitised`() {
        val parsed = CallPush.parse(
            mapOf(
                "callId" to "c1", "callToken" to "tok", "callKind" to "audio",
                "room" to "app_room@conference.host", "callerName" to "  Ann‮  Lee ",
                "callerXmppUsername" to "annlee@host", "group" to "true"
            )
        )
        assertEquals("c1", parsed.callId)
        assertEquals("tok", parsed.token)
        assertEquals(CallKind.AUDIO, parsed.kind)
        assertEquals("app_room", parsed.roomBareName)
        assertEquals("Ann Lee", parsed.callerName)
        assertEquals("annlee", parsed.callerXmppUsername)
        assertTrue(parsed.isGroup)
        assertFalse(parsed.isP2p)
    }

    @Test
    fun `the incoming call notification names the kind and the caller`() {
        val audio = CallPush.parse(mapOf("callId" to "c", "kind" to "audio", "callerName" to "Ann Lee"))
        assertEquals("Incoming audio call", CallPushText.title(audio))
        assertEquals("Ann Lee", CallPushText.body(audio))
        val group = CallPush.parse(mapOf("callId" to "c", "kind" to "video", "callToken" to "t", "group" to "1"))
        assertEquals("Incoming group video call", CallPushText.title(group))
        assertEquals("a nameless caller is not shown as a raw id", "Unknown caller", CallPushText.body(group))
    }

    @Test
    fun `a p2p push is never a group and a raw chat name is not a caller name`() {
        val parsed = CallPush.parse(
            mapOf("callId" to "c2", "callToken" to "", "mode" to "p2p", "group" to "true", "callerName" to "0123456789abcdef0123456789abcdef")
        )
        assertTrue(parsed.isP2p)
        assertFalse(parsed.isGroup)
        assertNull(parsed.token)
        assertEquals("", parsed.callerName)
    }

    @Test
    fun `a p2p push rings without a livekit token`() {
        val data = mapOf(
            "callId" to "p2p-9", "callToken" to "", "mode" to "p2p", "kind" to "video",
            "room" to "unitpush-bob@conf.test", "callerXmppUsername" to "bob", "callerName" to "Bob Marley"
        )
        assertTrue(CallSignaling.ringFromPush(data, enabled, now = 1L))
        val s = CallStore.snapshot()
        assertEquals(CallPhase.RINGING_INCOMING, s.phase)
        assertTrue(s.isP2p)
        assertNull(s.token)
        assertEquals("Bob Marley", s.roomName)
        assertEquals("bob", s.peerXmppUsername)
    }

    @Test
    fun `a livekit push without a token and any push while calls are off do not ring`() {
        assertFalse(CallSignaling.ringFromPush(mapOf("callId" to "c3", "kind" to "video", "room" to "r@conf.test"), enabled, now = 1L))
        assertFalse(
            CallSignaling.ringFromPush(
                mapOf("callId" to "c4", "callToken" to "tok", "kind" to "video", "room" to "r@conf.test"),
                VideoCallsConfig(enabled = false), now = 1L
            )
        )
        assertEquals(CallPhase.IDLE, CallStore.snapshot().phase)
    }
}
