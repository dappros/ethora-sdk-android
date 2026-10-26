package com.ethora.chat.core.call.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class P2PSignalInboxTest {

    private var clock = 0L
    private fun inbox(maxPendingCalls: Int = 8, maxPerCall: Int = 96) =
        P2PSignalInbox(ttlMs = 1_000, maxPendingCalls = maxPendingCalls, maxPerCall = maxPerCall, now = { clock })

    private fun sig(callId: String, text: String = "x") = P2PSignal(
        callId, P2PSignalName.CANDIDATE, P2PPayload.Candidate(text, "0", 0), "bob@h", "bob", null, false
    )

    @Test
    fun `signals for a call with no listener are held and replayed in order on attach`() {
        val inbox = inbox()
        assertEquals(P2PSignalInbox.Delivery.BUFFERED, inbox.receive(sig("c1", "a")))
        inbox.receive(sig("c1", "b"))
        val seen = mutableListOf<String>()
        inbox.attach("c1") { seen += (it.payload as P2PPayload.Candidate).candidate }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(P2PSignalInbox.Delivery.DELIVERED, inbox.receive(sig("c1", "c")))
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `held signals expire`() {
        val inbox = inbox()
        inbox.receive(sig("c1"))
        clock = 5_000
        val seen = mutableListOf<P2PSignal>()
        inbox.attach("c1") { seen += it }
        assertEquals(0, seen.size)
    }

    @Test
    fun `detaching closes the call and stragglers are dropped`() {
        val inbox = inbox()
        val detach = inbox.attach("c1") {}
        detach()
        assertEquals(P2PSignalInbox.Delivery.DROPPED, inbox.receive(sig("c1")))
        // A new attach for the same id (a later call reusing it) reopens it.
        inbox.attach("c1") {}
        assertEquals(P2PSignalInbox.Delivery.DELIVERED, inbox.receive(sig("c1")))
    }

    @Test
    fun `unknown calls are bounded`() {
        val inbox = inbox(maxPendingCalls = 2, maxPerCall = 2)
        inbox.receive(sig("a")); inbox.receive(sig("b")); inbox.receive(sig("c"))
        val a = mutableListOf<P2PSignal>()
        inbox.attach("a") { a += it }
        assertEquals("oldest pending call was evicted", 0, a.size)

        inbox.receive(sig("d")); inbox.receive(sig("d"))
        assertEquals(P2PSignalInbox.Delivery.DROPPED, inbox.receive(sig("d")))
    }
}
