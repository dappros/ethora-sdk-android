package com.ethora.chat.core.call

import com.ethora.chat.core.call.p2p.P2PRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of the single active call. Mirrors web `CallPhase` (roomStore/callSlice.ts). */
enum class CallPhase {
    IDLE,
    REQUESTING,
    RINGING_INCOMING,
    CONNECTING,
    IN_CALL,
    ERROR
}

enum class CallDirection { OUTGOING, INCOMING }

enum class CallKind {
    AUDIO,
    VIDEO;

    /** Wire value used in `<data kind="...">` and the REST body. */
    val wire: String
        get() = if (this == AUDIO) "audio" else "video"

    companion object {
        /** Anything that is not "audio" is video, matching web's default. */
        fun fromWire(value: String?): CallKind =
            if (value?.trim()?.equals("audio", ignoreCase = true) == true) AUDIO else VIDEO
    }
}

/**
 * Snapshot of the active call. [token] is the LiveKit access token delivered
 * by the server's `call-token` broadcast; the session connects with it.
 */
data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    val direction: CallDirection? = null,
    val kind: CallKind = CallKind.VIDEO,
    val roomJid: String? = null,
    val roomName: String? = null,
    /** Bare room name (JID localpart). Call signals reference calls by this value. */
    val roomBareName: String? = null,
    val callId: String? = null,
    /** Peer's XMPP localpart (no `@host`). */
    val peerXmppUsername: String? = null,
    val token: String? = null,
    /** LiveKit URL the server sent with the call-token; null means use the configured one. */
    val livekitUrl: String? = null,
    /** Group room call (LiveKit). */
    val isGroup: Boolean = false,
    /** How the server carries the call: `livekit` (default) or `p2p` (our own WebRTC, no LiveKit token). */
    val mode: String = CallStanzas.MODE_LIVEKIT,
    /** STUN/TURN servers of a p2p call; empty means the session fetches them. */
    val iceServers: List<IceServerConfig> = emptyList(),
    val error: String? = null,
    val startedAt: Long? = null,
    /** Set once the call connects; used for the local "call ended" log duration. */
    val connectedAt: Long? = null
) {
    val isP2p: Boolean get() = mode == CallStanzas.MODE_P2P
}

/**
 * Single source of truth for the active call. Each function is the Kotlin
 * counterpart of one reducer in web `callSlice.ts`. Timestamps are parameters
 * so the transitions can be tested deterministically.
 */
object CallStore {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    fun snapshot(): CallState = _state.value

    private val releaseHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Registers [hook] to run whenever a call is over (hang up, decline, error): the media
     * session, the audio route and the ongoing-call notification are released from here, so they
     * never outlive the call even when no call screen is on display.
     */
    fun addReleaseHook(hook: () -> Unit) {
        if (hook !in releaseHooks) releaseHooks.add(hook)
    }

    private fun releaseSessions() {
        P2PRuntime.disposeActive()
        for (hook in releaseHooks) {
            try {
                hook()
            } catch (_: Exception) {
                // One failing release must not stop the others.
            }
        }
    }

    fun startOutgoingCall(
        roomJid: String,
        roomName: String?,
        roomBareName: String?,
        kind: CallKind,
        peerXmppUsername: String?,
        now: Long = System.currentTimeMillis()
    ) {
        _state.value = CallState(
            phase = CallPhase.REQUESTING,
            direction = CallDirection.OUTGOING,
            kind = kind,
            roomJid = roomJid,
            roomName = roomName,
            roomBareName = roomBareName?.takeIf { it.isNotBlank() } ?: roomJid.substringBefore("@"),
            peerXmppUsername = peerXmppUsername?.takeIf { it.isNotBlank() },
            startedAt = now
        )
    }

    fun setIncomingCallToken(
        roomJid: String,
        roomName: String?,
        roomBareName: String?,
        token: String,
        kind: CallKind,
        callId: String?,
        peerXmppUsername: String?,
        now: Long = System.currentTimeMillis(),
        livekitUrl: String? = null,
        isGroup: Boolean = false,
        mode: String = CallStanzas.MODE_LIVEKIT,
        iceServers: List<IceServerConfig> = emptyList()
    ) {
        _state.value = CallState(
            phase = CallPhase.RINGING_INCOMING,
            direction = CallDirection.INCOMING,
            kind = kind,
            roomJid = roomJid,
            roomName = roomName,
            roomBareName = roomBareName?.takeIf { it.isNotBlank() } ?: roomJid.substringBefore("@"),
            callId = callId?.takeIf { it.isNotBlank() },
            peerXmppUsername = peerXmppUsername?.takeIf { it.isNotBlank() },
            // A p2p call has no LiveKit token.
            token = token.takeIf { it.isNotBlank() },
            livekitUrl = livekitUrl?.takeIf { it.isNotBlank() },
            isGroup = isGroup,
            mode = mode,
            iceServers = iceServers,
            startedAt = now
        )
    }

    /**
     * Caller side: the create response says how the server carries the call. [startedAt] is the
     * dial it answers; returns false (and changes nothing) when that dial is no longer the
     * current call (cancelled or replaced while the request was in flight).
     */
    fun setOutgoingRoute(
        startedAt: Long?,
        callId: String?,
        mode: String,
        group: Boolean,
        livekitUrl: String?,
        iceServers: List<IceServerConfig>
    ): Boolean {
        val current = _state.value
        if (current.phase == CallPhase.IDLE ||
            current.direction != CallDirection.OUTGOING ||
            current.startedAt != startedAt
        ) {
            return false
        }
        _state.value = current.copy(
            callId = callId?.takeIf { it.isNotBlank() } ?: current.callId,
            mode = mode,
            isGroup = group,
            livekitUrl = livekitUrl?.takeIf { it.isNotBlank() } ?: current.livekitUrl,
            iceServers = iceServers
        )
        return true
    }

    /**
     * Caller side: the server's call-token arrived for our dial. Returns false
     * (and changes nothing) when there is no matching outgoing call.
     */
    fun setOutgoingCallToken(
        roomJid: String,
        token: String,
        callId: String?,
        now: Long = System.currentTimeMillis(),
        livekitUrl: String? = null
    ): Boolean {
        val current = _state.value
        if (current.direction != CallDirection.OUTGOING) return false
        val currentRoom = current.roomJid.orEmpty()
        if (currentRoom.isNotEmpty() && currentRoom != roomJid) return false
        _state.value = current.copy(
            phase = CallPhase.CONNECTING,
            roomJid = roomJid,
            token = token,
            livekitUrl = livekitUrl?.takeIf { it.isNotBlank() } ?: current.livekitUrl,
            callId = callId?.takeIf { it.isNotBlank() } ?: current.callId,
            error = null,
            startedAt = current.startedAt ?: now
        )
        return true
    }

    fun acceptIncomingCall(now: Long = System.currentTimeMillis()) {
        val current = _state.value
        if (current.direction != CallDirection.INCOMING) return
        _state.value = current.copy(
            phase = CallPhase.CONNECTING,
            error = null,
            startedAt = current.startedAt ?: now
        )
    }

    fun setCallKind(kind: CallKind) {
        val current = _state.value
        if (current.phase == CallPhase.IDLE) return
        _state.value = current.copy(kind = kind)
    }

    fun setCallError(message: String, now: Long = System.currentTimeMillis()) {
        // The media session of a failed call is released with it.
        releaseSessions()
        val current = _state.value
        _state.value = current.copy(
            phase = CallPhase.ERROR,
            error = message,
            startedAt = current.startedAt ?: now
        )
    }

    /**
     * The LiveKit session is up. The connect moment is anchored once so a
     * mid-call reconnect does not reset the duration of the call log.
     */
    fun markInCall(now: Long = System.currentTimeMillis()) {
        val current = _state.value
        _state.value = current.copy(
            phase = CallPhase.IN_CALL,
            error = null,
            connectedAt = current.connectedAt ?: now
        )
    }

    /** Ends, declines, dismisses or cancels: back to the idle state. */
    fun reset() {
        releaseSessions()
        _state.value = CallState()
    }
}
