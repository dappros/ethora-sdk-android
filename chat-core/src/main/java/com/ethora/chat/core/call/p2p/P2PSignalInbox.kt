package com.ethora.chat.core.call.p2p

/**
 * Routes incoming p2p signals to the call that owns them (web `calls/p2p/signalInbox.ts`).
 *
 * Signals can arrive before anyone is listening: the caller's offer and first candidates
 * are on the wire while the callee is still on the ring screen (the controller only exists
 * after Accept), and may even overtake the relayed call-token that tells the callee the
 * call exists. So signals for a callId with no listener are held briefly and replayed in
 * order when a controller attaches. The hold is small and bounded on purpose: a signal for a
 * call that never materialises is just dropped when its time is up, so unknown callIds stay
 * inert (they never raise UI and never grow memory without limit).
 */
class P2PSignalInbox(
    private val ttlMs: Long = 20_000,
    private val maxPendingCalls: Int = 8,
    private val maxPerCall: Int = 96,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class Delivery { DELIVERED, BUFFERED, DROPPED }

    private class Pending(val firstAt: Long, val signals: MutableList<P2PSignal> = mutableListOf())

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private val handlers = HashMap<String, (P2PSignal) -> Unit>()

    // Calls that already ended: late stragglers for them are dropped instead of being
    // buffered for a controller that will never come.
    private val closed = ArrayDeque<String>()

    private fun sweep() {
        val cutoff = now() - ttlMs
        pending.entries.removeAll { it.value.firstAt < cutoff }
    }

    fun receive(signal: P2PSignal): Delivery {
        val handler = synchronized(lock) { handlers[signal.callId] }
        if (handler != null) {
            handler(signal)
            return Delivery.DELIVERED
        }
        synchronized(lock) {
            if (signal.callId in closed) return Delivery.DROPPED
            sweep()
            var entry = pending[signal.callId]
            if (entry == null) {
                if (pending.size >= maxPendingCalls) {
                    pending.remove(pending.keys.first())
                }
                entry = Pending(now())
                pending[signal.callId] = entry
            }
            if (entry.signals.size >= maxPerCall) return Delivery.DROPPED
            entry.signals.add(signal)
            return Delivery.BUFFERED
        }
    }

    /**
     * Starts delivering a call's signals to [handler], replaying anything held for it first
     * (in arrival order). Returns a detach function that also marks the call closed.
     */
    fun attach(callId: String, handler: (P2PSignal) -> Unit): () -> Unit {
        val held = synchronized(lock) {
            closed.remove(callId)
            sweep()
            val entry = pending.remove(callId)
            handlers[callId] = handler
            entry?.signals?.toList().orEmpty()
        }
        for (signal in held) handler(signal)
        return {
            synchronized(lock) {
                if (handlers[callId] === handler) handlers.remove(callId)
            }
            close(callId)
        }
    }

    /** The call is over: forget held signals and drop future stragglers. */
    fun close(callId: String) {
        synchronized(lock) {
            pending.remove(callId)
            if (callId !in closed) {
                closed.addLast(callId)
                if (closed.size > CLOSED_MEMORY) closed.removeFirst()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            handlers.clear()
            closed.clear()
        }
    }

    private companion object {
        const val CLOSED_MEMORY = 16
    }
}
