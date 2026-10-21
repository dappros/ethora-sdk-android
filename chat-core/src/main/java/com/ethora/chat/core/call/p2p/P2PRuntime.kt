package com.ethora.chat.core.call.p2p

import com.ethora.chat.core.call.CallStanzas
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The glue between the XMPP stanza stream, the call state and the per-call controller
 * (web `calls/p2p/p2pRuntime.ts`). Android and XMPP specifics live here so the controller and
 * the pure classes stay free of them.
 */
object P2PRuntime {
    /** How long a released controller survives, so a recreated screen (rotation) can take it back. */
    private const val RELEASE_GRACE_MS = 1_500L

    /** Signals that arrive for a call nobody is handling yet wait here. */
    val inbox = P2PSignalInbox()

    private class Active(val key: String, val controller: P2PCallController, val detach: () -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var active: Active? = null
    private var pendingRelease: Job? = null

    /**
     * Entry point for an incoming `call-p2p` message. Stale, replayed or self-sent signals are
     * dropped; the rest go to the inbox, which delivers or briefly holds them.
     */
    fun onSignal(signal: P2PSignal, selfXmppUsername: String) {
        if (signal.fromLocal.isEmpty()) return
        if (!P2PSignalStanza.isFresh(signal)) return
        if (signal.fromLocal == selfXmppUsername.substringBefore("@").lowercase()) return
        inbox.receive(signal)
    }

    /** Sends one signal straight to the peer's bare JID over the live XMPP client. */
    fun sendSignal(
        client: XMPPClient?,
        peerXmppUsername: String,
        callId: String,
        signal: P2PSignalName,
        payload: P2PPayload
    ): Boolean {
        val peer = peerXmppUsername.trim()
        if (client == null || peer.isEmpty()) return false
        val stanza = P2PSignalStanza.build(CallStanzas.peerJid(peer, client.xmppHost()), callId, signal, payload)
        return client.sendCallSignal(stanza)
    }

    /**
     * Returns the controller for [key], creating it on first use and wiring it to the inbox.
     * A screen that is recreated right after being torn down gets the SAME controller back
     * instead of ending the call and starting a second negotiation.
     */
    fun acquire(key: String, create: () -> P2PCallController): P2PCallController {
        synchronized(lock) {
            pendingRelease?.cancel()
            pendingRelease = null
            active?.let { if (it.key == key) return it.controller }
            active?.let { stop(it) }
            val controller = create()
            val detach = inbox.attach(controller.callId) { controller.receive(it) }
            active = Active(key, controller, detach)
            return controller
        }
    }

    /** The screen showing [key] went away; the controller is disposed after a short grace period. */
    fun release(key: String) {
        synchronized(lock) {
            val current = active ?: return
            if (current.key != key) return
            pendingRelease?.cancel()
            pendingRelease = scope.launch {
                delay(RELEASE_GRACE_MS)
                synchronized(lock) {
                    if (active === current) {
                        active = null
                        stop(current)
                    }
                }
            }
        }
    }

    /** The call is over (hang up, decline, error): release the devices now. */
    fun disposeActive() {
        synchronized(lock) {
            pendingRelease?.cancel()
            pendingRelease = null
            val current = active ?: return
            active = null
            stop(current)
        }
    }

    private fun stop(current: Active) {
        current.controller.dispose()
        current.detach()
    }
}
