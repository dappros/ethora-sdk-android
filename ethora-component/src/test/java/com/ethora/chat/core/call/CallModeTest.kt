package com.ethora.chat.core.call

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallModeTest {

    @Test
    fun `ice servers keep only well formed stun and turn entries`() {
        val json = """
            [
              {"urls": ["turn:t.example:3478?transport=udp", "turns:t.example:5349"], "username": "u", "credential": "p"},
              {"url": "stun:s.example:3478"},
              {"urls": "http://not-ice"},
              {"urls": []},
              "garbage",
              {"urls": ["STUN:upper.example"]}
            ]
        """.trimIndent()
        val list = normalizeIceServers(json)
        assertEquals(3, list.size)
        assertEquals(IceServerConfig(listOf("turn:t.example:3478?transport=udp", "turns:t.example:5349"), "u", "p"), list[0])
        assertEquals(IceServerConfig(listOf("stun:s.example:3478")), list[1])
        assertEquals(listOf("STUN:upper.example"), list[2].urls)
    }

    @Test
    fun `ice servers accept an envelope and never throw on junk`() {
        assertEquals(1, normalizeIceServers("""{"iceServers":[{"urls":"stun:s"}]}""").size)
        assertTrue(normalizeIceServers("not json").isEmpty())
        assertTrue(normalizeIceServers("").isEmpty())
        assertTrue(normalizeIceServers(null as String?).isEmpty())
        assertTrue(normalizeIceServers("42").isEmpty())
    }

    @Test
    fun `create response of an old server maps to livekit`() {
        val r = parseCallCreateResponse(JsonParser.parseString("""{"ok":true,"callId":"c1"}""").asJsonObject, CallKind.AUDIO)
        assertEquals("c1", r.callId)
        assertEquals("livekit", r.mode)
        assertFalse(r.isP2p)
        assertEquals(CallKind.AUDIO, r.kind)
        assertNull(r.group)
        assertNull(r.livekitUrl)
    }

    @Test
    fun `create response of a p2p call carries ice servers and ttl`() {
        val r = parseCallCreateResponse(
            JsonParser.parseString(
                """{"ok":true,"callId":"c2","mode":"p2p","kind":"video","iceServers":[{"urls":["turn:t"],"username":"u","credential":"p"}],"ttlSeconds":3600}"""
            ).asJsonObject,
            CallKind.AUDIO
        )
        assertTrue(r.isP2p)
        assertEquals(CallKind.VIDEO, r.kind)
        assertEquals(1, r.iceServers.size)
        assertEquals(3600L, r.ttlSeconds)
    }

    @Test
    fun `create response of a livekit call carries the group flag and a valid url only`() {
        val ok = parseCallCreateResponse(
            JsonParser.parseString("""{"callId":"c3","mode":"livekit","group":true,"livekitUrl":"wss://lk.example"}""").asJsonObject,
            CallKind.VIDEO
        )
        assertEquals(true, ok.group)
        assertEquals("wss://lk.example", ok.livekitUrl)
        val bad = parseCallCreateResponse(
            JsonParser.parseString("""{"callId":"c3","livekitUrl":"javascript:alert(1)"}""").asJsonObject, CallKind.VIDEO
        )
        assertNull(bad.livekitUrl)
    }

    @Test
    fun `a p2p create response without a callId is unusable`() {
        try {
            parseCallCreateResponse(JsonParser.parseString("""{"mode":"p2p"}""").asJsonObject, CallKind.VIDEO)
            fail("expected an exception")
        } catch (_: IllegalStateException) {
        }
    }

    private fun busy(
        phase: CallPhase = CallPhase.REQUESTING,
        direction: CallDirection? = CallDirection.OUTGOING,
        mode: String = "p2p",
        callId: String? = "m",
        room: String? = "room1"
    ) = BusyCallSnapshot(phase, direction, mode, callId, room)

    @Test
    fun `incoming call decisions while busy`() {
        assertEquals(IncomingWhileBusy.ACCEPT, decideIncomingWhileBusy(busy(phase = CallPhase.IDLE), "p2p", "x", "room1"))
        assertEquals(IncomingWhileBusy.DUPLICATE, decideIncomingWhileBusy(busy(), "p2p", "m", "room1"))
        assertEquals(
            "livekit over livekit keeps the legacy overwrite",
            IncomingWhileBusy.REPLACE,
            decideIncomingWhileBusy(busy(mode = "livekit"), "livekit", "x", "room9")
        )
        assertEquals(IncomingWhileBusy.BUSY, decideIncomingWhileBusy(busy(), "p2p", "x", "other-room"))
        assertEquals(
            IncomingWhileBusy.BUSY,
            decideIncomingWhileBusy(busy(phase = CallPhase.IN_CALL, direction = CallDirection.INCOMING), "p2p", "a", "room1")
        )
    }

    @Test
    fun `both sides dialling each other - the smaller callId wins on both ends`() {
        assertEquals(IncomingWhileBusy.GLARE_YIELD, decideIncomingWhileBusy(busy(callId = "m"), "p2p", "a", "room1"))
        assertEquals(IncomingWhileBusy.GLARE_KEEP, decideIncomingWhileBusy(busy(callId = "m"), "p2p", "z", "room1"))
    }
}
