package com.ethora.chat.core.call.p2p

/**
 * The pure half of the p2p signalling state machine (web `calls/p2p/negotiator.ts`): given
 * the stream of signals from the peer and a few "we are ready" notifications, it says WHAT
 * to do next (apply an offer, apply an answer, add a candidate, stop) without touching a
 * peer connection. The controller executes the commands.
 *
 * Rules it enforces:
 *  - one negotiation per callId; signals for another callId or from anyone but the expected
 *    peer are ignored;
 *  - the callee may receive the offer before it has accepted (and before it has local
 *    media): the first offer wins, later initial offers are ignored, and it is held until
 *    [markReady];
 *  - candidates that arrive before the remote description is applied are held and flushed
 *    right after it;
 *  - duplicates (same SDP delivered twice, e.g. the caller re-sending its offer) are no-ops;
 *  - after the first exchange, a new offer is a renegotiation (ICE restart). If both sides
 *    restart at once (glare) the caller is "impolite" and keeps its own offer; the callee is
 *    "polite" and yields to the caller's.
 *
 * Not thread safe: the controller drives it from one thread.
 */
enum class P2PRole { CALLER, CALLEE }

sealed interface NegotiatorCommand {
    data class ApplyOffer(val sdp: String, val initial: Boolean, val rollback: Boolean) : NegotiatorCommand
    data class ApplyAnswer(val sdp: String) : NegotiatorCommand
    data class AddCandidate(val candidate: P2PPayload.Candidate) : NegotiatorCommand
    data class RemoteEnd(val reason: String) : NegotiatorCommand
}

class P2PNegotiator(
    val callId: String,
    val role: P2PRole,
    peerLocal: String
) {
    private val peerLocal: String = peerLocal.substringBefore("@").lowercase()

    private var ready = false
    var isEnded = false
        private set
    private var remoteDescriptionSet = false
    private var initialOfferTaken = false
    private var heldOffer: String? = null
    private var localOfferPending = false
    private var lastOfferSdp: String? = null
    private var lastAnswerSdp: String? = null
    private var heldCandidates = mutableListOf<P2PPayload.Candidate>()

    // The caller re-sends its offer and candidates while unanswered, so the same candidate
    // can arrive more than once; each is applied (or held) only once.
    private val seenCandidates = HashSet<String>()

    /** True while we have sent an offer and not yet received its answer. */
    val awaitingAnswer: Boolean get() = localOfferPending

    private fun accepts(signal: P2PSignal): Boolean {
        if (isEnded) return false
        if (signal.callId != callId) return false
        if (peerLocal.isNotEmpty() && signal.fromLocal != peerLocal) return false
        return true
    }

    fun receive(signal: P2PSignal): List<NegotiatorCommand> {
        if (!accepts(signal)) return emptyList()
        when (signal.signal) {
            P2PSignalName.END -> {
                isEnded = true
                heldOffer = null
                heldCandidates = mutableListOf()
                val reason = (signal.payload as? P2PPayload.End)?.reason
                return listOf(NegotiatorCommand.RemoteEnd(reason?.takeIf { it.isNotEmpty() } ?: "ended"))
            }

            P2PSignalName.OFFER -> {
                val sdp = (signal.payload as? P2PPayload.Sdp)?.sdp ?: return emptyList()
                if (sdp == lastOfferSdp) return emptyList()
                if (!initialOfferTaken) {
                    // Only the callee ever receives the first offer.
                    if (role != P2PRole.CALLEE) return emptyList()
                    initialOfferTaken = true
                    lastOfferSdp = sdp
                    if (!ready) {
                        heldOffer = sdp
                        return emptyList()
                    }
                    return listOf(NegotiatorCommand.ApplyOffer(sdp, initial = true, rollback = false))
                }
                // Renegotiation. Before the initial exchange finished, or before we are
                // ready, there is nothing sensible to apply it to.
                if (!ready || !remoteDescriptionSet) return emptyList()
                if (localOfferPending) {
                    if (role == P2PRole.CALLER) return emptyList() // impolite: keep ours
                    localOfferPending = false // polite: roll ours back
                    lastOfferSdp = sdp
                    return listOf(NegotiatorCommand.ApplyOffer(sdp, initial = false, rollback = true))
                }
                lastOfferSdp = sdp
                return listOf(NegotiatorCommand.ApplyOffer(sdp, initial = false, rollback = false))
            }

            P2PSignalName.ANSWER -> {
                if (!localOfferPending) return emptyList()
                val sdp = (signal.payload as? P2PPayload.Sdp)?.sdp ?: return emptyList()
                if (sdp == lastAnswerSdp) return emptyList()
                lastAnswerSdp = sdp
                localOfferPending = false
                return listOf(NegotiatorCommand.ApplyAnswer(sdp))
            }

            P2PSignalName.CANDIDATE -> {
                val c = signal.payload as? P2PPayload.Candidate ?: return emptyList()
                // An empty candidate string is the end-of-candidates marker; nothing to add.
                if (c.candidate.isEmpty()) return emptyList()
                val key = "${c.candidate}|${c.sdpMid.orEmpty()}|${c.sdpMLineIndex?.toString().orEmpty()}"
                if (!seenCandidates.add(key)) return emptyList()
                if (remoteDescriptionSet) return listOf(NegotiatorCommand.AddCandidate(c))
                if (heldCandidates.size < MAX_HELD_CANDIDATES) heldCandidates.add(c)
                return emptyList()
            }
        }
    }

    /**
     * Local media and the peer connection exist. For the callee this releases the offer that
     * arrived while it was still on the ring screen.
     */
    fun markReady(): List<NegotiatorCommand> {
        if (isEnded) return emptyList()
        ready = true
        val sdp = heldOffer ?: return emptyList()
        heldOffer = null
        return listOf(NegotiatorCommand.ApplyOffer(sdp, initial = true, rollback = false))
    }

    /** We created and set a local offer; its answer is now expected. */
    fun markLocalOffer() {
        localOfferPending = true
        lastAnswerSdp = null
        // The caller's own first offer IS its initial exchange, so any offer it receives
        // afterwards is a renegotiation, not a second "first offer".
        if (role == P2PRole.CALLER) initialOfferTaken = true
    }

    /**
     * A remote description (offer or answer) was applied to the connection. Returns the
     * candidates that were waiting for it.
     */
    fun markRemoteApplied(): List<NegotiatorCommand> {
        remoteDescriptionSet = true
        val held = heldCandidates
        heldCandidates = mutableListOf()
        return held.map { NegotiatorCommand.AddCandidate(it) }
    }

    /** Local teardown: later signals are ignored. */
    fun stop() {
        isEnded = true
        heldOffer = null
        heldCandidates = mutableListOf()
    }

    private companion object {
        const val MAX_HELD_CANDIDATES = 64
    }
}
