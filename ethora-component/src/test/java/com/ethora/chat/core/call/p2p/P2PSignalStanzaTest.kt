package com.ethora.chat.core.call.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2PSignalStanzaTest {

    private val now = 1_750_000_000_000L

    /** What the receiving side sees: the built stanza with a sender and a stanza-id added. */
    private fun received(stanza: String, from: String = "Alice@Host/res", stamp: Long? = null): String {
        val withFrom = stanza.replaceFirst("<message ", "<message from=\"$from\" ")
        return if (stamp == null) withFrom else withFrom.replace("<body>", "<stanza-id xmlns=\"urn:xmpp:sid:0\" id=\"$stamp\" by=\"x\"/><body>")
    }

    @Test
    fun `offer round trips and keeps a multi line sdp intact`() {
        val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\na=ice-ufrag:abc\"<>&'\r\n"
        val xml = P2PSignalStanza.build("bob@host", "call-1", P2PSignalName.OFFER, P2PPayload.Sdp("offer", sdp), now)
        assertTrue(xml.contains("type=\"call-p2p\""))
        assertTrue(xml.contains("<no-store"))
        val signal = P2PSignalStanza.parse(received(xml), now)!!
        assertEquals("call-1", signal.callId)
        assertEquals(P2PSignalName.OFFER, signal.signal)
        assertEquals(P2PPayload.Sdp("offer", sdp), signal.payload)
        assertEquals("alice@host", signal.fromBare)
        assertEquals("alice", signal.fromLocal)
        assertFalse(signal.delayed)
    }

    @Test
    fun `candidate and end payloads round trip`() {
        val candidate = P2PPayload.Candidate("candidate:1 1 udp 2122 10.0.0.1 5000 typ host", "0", 0, "frag")
        val c = P2PSignalStanza.parse(
            received(P2PSignalStanza.build("b@h", "c", P2PSignalName.CANDIDATE, candidate, now)), now
        )!!
        assertEquals(candidate, c.payload)

        val end = P2PSignalStanza.parse(
            received(P2PSignalStanza.build("b@h", "c", P2PSignalName.END, P2PPayload.End("media-error"), now)), now
        )!!
        assertEquals(P2PPayload.End("media-error"), end.payload)
    }

    @Test
    fun `a payload attribute that contains a raw greater-than still parses`() {
        val xml = """<message from="a@h/r" to="b@h" type="chat"><data type="call-p2p" callId="c" signal="end" payload='{"reason":"a>b"}'/></message>"""
        val signal = P2PSignalStanza.parse(xml, now)!!
        assertEquals(P2PPayload.End("a>b"), signal.payload)
    }

    @Test
    fun `malformed signals are rejected`() {
        fun parse(attrs: String) = P2PSignalStanza.parse(
            """<message from="a@h"><data type="call-p2p" $attrs/></message>""", now
        )
        assertNull(parse("""signal="offer" payload="{}""""))
        assertNull(parse("""callId="c" signal="bogus" payload="{}""""))
        assertNull(parse("""callId="c" signal="offer" payload="not json""""))
        assertNull("offer needs type and sdp", parse("""callId="c" signal="offer" payload="{&quot;type&quot;:&quot;answer&quot;,&quot;sdp&quot;:&quot;x&quot;}""""))
        assertNull("candidate needs a string", parse("""callId="c" signal="candidate" payload="{}""""))
        assertNull(P2PSignalStanza.parse("<message><data type=\"call-token\"/></message>", now))
    }

    @Test
    fun `an oversized sdp is rejected and a long reason is cut`() {
        val huge = "x".repeat(70 * 1024)
        val offer = P2PSignalStanza.build("b@h", "c", P2PSignalName.OFFER, P2PPayload.Sdp("offer", huge), now)
        assertNull(P2PSignalStanza.parse(received(offer), now))

        val end = P2PSignalStanza.build("b@h", "c", P2PSignalName.END, P2PPayload.End("r".repeat(200)), now)
        val reason = (P2PSignalStanza.parse(received(end), now)!!.payload as P2PPayload.End).reason!!
        assertEquals(64, reason.length)
    }

    @Test
    fun `freshness follows the stanza-id timestamp and replay markers`() {
        val build = { name: P2PSignalName, p: P2PPayload -> P2PSignalStanza.build("b@h", "c", name, p, now) }
        val candidate = P2PPayload.Candidate("cand", "0", 0)

        val live = P2PSignalStanza.parse(received(build(P2PSignalName.CANDIDATE, candidate), stamp = now * 1000 - 2_000_000), now)!!
        assertEquals(2_000L, live.ageMs)
        assertTrue(P2PSignalStanza.isFresh(live))

        val old = P2PSignalStanza.parse(received(build(P2PSignalName.CANDIDATE, candidate), stamp = now * 1000 - 60_000_000), now)!!
        assertFalse(P2PSignalStanza.isFresh(old))

        val noStamp = P2PSignalStanza.parse(received(build(P2PSignalName.CANDIDATE, candidate)), now)!!
        assertNull(noStamp.ageMs)
        assertTrue(P2PSignalStanza.isFresh(noStamp))

        val delayedXml = received(build(P2PSignalName.END, P2PPayload.End("x"))).replace("<body>", "<delay xmlns=\"urn:xmpp:delay\"/><body>")
        assertFalse("a replayed end is stale", P2PSignalStanza.isFresh(P2PSignalStanza.parse(delayedXml, now)!!))

        val oldEnd = P2PSignalStanza.parse(received(build(P2PSignalName.END, P2PPayload.End("x")), stamp = now * 1000 - 600_000_000), now)!!
        assertTrue("an end is accepted however old unless replayed", P2PSignalStanza.isFresh(oldEnd))
    }
}
