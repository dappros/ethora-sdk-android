package com.ethora.chat.core.call.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2PNegotiatorTest {

    private fun sig(
        signal: P2PSignalName,
        payload: P2PPayload,
        from: String = "bob",
        callId: String = "c1"
    ) = P2PSignal(callId, signal, payload, "$from@h", from, null, false)

    private fun offer(sdp: String = "offer-1", from: String = "bob") = sig(P2PSignalName.OFFER, P2PPayload.Sdp("offer", sdp), from)
    private fun answer(sdp: String = "answer-1") = sig(P2PSignalName.ANSWER, P2PPayload.Sdp("answer", sdp))
    private fun cand(text: String) = sig(P2PSignalName.CANDIDATE, P2PPayload.Candidate(text, "0", 0))

    private fun callee() = P2PNegotiator("c1", P2PRole.CALLEE, "bob")
    private fun caller() = P2PNegotiator("c1", P2PRole.CALLER, "bob")

    @Test
    fun `callee holds the offer until it is ready`() {
        val n = callee()
        assertTrue(n.receive(offer()).isEmpty())
        assertEquals(listOf(NegotiatorCommand.ApplyOffer("offer-1", initial = true, rollback = false)), n.markReady())
        assertTrue("released once", n.markReady().isEmpty())
    }

    @Test
    fun `callee applies the offer at once when already ready, and ignores a second initial offer`() {
        val n = callee()
        n.markReady()
        assertEquals(1, n.receive(offer()).size)
        n.markRemoteApplied()
        assertTrue("same sdp again is a no-op", n.receive(offer()).isEmpty())
    }

    @Test
    fun `a caller never takes a first offer`() {
        val n = caller()
        n.markReady()
        assertTrue(n.receive(offer()).isEmpty())
    }

    @Test
    fun `signals for another call or another peer are ignored`() {
        val n = callee()
        n.markReady()
        assertTrue(n.receive(sig(P2PSignalName.OFFER, P2PPayload.Sdp("offer", "x"), callId = "other")).isEmpty())
        assertTrue(n.receive(offer(from = "mallory")).isEmpty())
    }

    @Test
    fun `candidates wait for the remote description and are applied once`() {
        val n = callee()
        n.markReady()
        n.receive(offer())
        assertTrue(n.receive(cand("a")).isEmpty())
        assertTrue("duplicate while held", n.receive(cand("a")).isEmpty())
        n.receive(cand("b"))
        val flushed = n.markRemoteApplied()
        assertEquals(
            listOf("a", "b"),
            flushed.map { (it as NegotiatorCommand.AddCandidate).candidate.candidate }
        )
        assertEquals(1, n.receive(cand("c")).size)
        assertTrue("duplicate after applied", n.receive(cand("c")).isEmpty())
    }

    @Test
    fun `an empty candidate is the end-of-candidates marker`() {
        val n = callee()
        n.markReady()
        n.markRemoteApplied()
        assertTrue(n.receive(cand("")).isEmpty())
    }

    @Test
    fun `an answer is only taken while our offer is pending`() {
        val n = caller()
        n.markReady()
        assertTrue(n.receive(answer()).isEmpty())
        n.markLocalOffer()
        assertTrue(n.awaitingAnswer)
        assertEquals(listOf(NegotiatorCommand.ApplyAnswer("answer-1")), n.receive(answer()))
        assertFalse(n.awaitingAnswer)
        assertTrue("duplicate answer", n.receive(answer()).isEmpty())
    }

    @Test
    fun `glare on a restart - the caller keeps its offer, the callee rolls back`() {
        val caller = caller()
        caller.markReady(); caller.markLocalOffer(); caller.receive(answer()); caller.markRemoteApplied()
        caller.markLocalOffer() // ICE restart offer in flight
        assertTrue(caller.receive(offer("restart-from-callee")).isEmpty())

        val callee = callee()
        callee.markReady(); callee.receive(offer()); callee.markRemoteApplied()
        callee.markLocalOffer()
        val commands = callee.receive(offer("restart-from-caller"))
        assertEquals(listOf(NegotiatorCommand.ApplyOffer("restart-from-caller", initial = false, rollback = true)), commands)
        assertFalse(callee.awaitingAnswer)
    }

    @Test
    fun `a renegotiation offer is a plain apply when nothing is pending`() {
        val n = callee()
        n.markReady(); n.receive(offer()); n.markRemoteApplied()
        assertEquals(
            listOf(NegotiatorCommand.ApplyOffer("restart", initial = false, rollback = false)),
            n.receive(offer("restart"))
        )
    }

    @Test
    fun `end stops the negotiation and later signals are ignored`() {
        val n = callee()
        val commands = n.receive(sig(P2PSignalName.END, P2PPayload.End("media-error")))
        assertEquals(listOf(NegotiatorCommand.RemoteEnd("media-error")), commands)
        assertTrue(n.isEnded)
        assertTrue(n.receive(offer()).isEmpty())
        assertTrue(n.markReady().isEmpty())
    }

    @Test
    fun `end without a reason reports ended`() {
        val n = caller()
        assertEquals(listOf(NegotiatorCommand.RemoteEnd("ended")), n.receive(sig(P2PSignalName.END, P2PPayload.End(null))))
    }
}
