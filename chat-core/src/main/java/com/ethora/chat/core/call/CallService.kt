package com.ethora.chat.core.call

import com.ethora.chat.core.config.VideoCallsConfig
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.networking.CallsAPIHelper
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Outgoing and call-control actions. Port of the dial and hang-up logic in web
 * `ChatHeader.placeCall` and `VideoCallOverlay.terminateCall`.
 *
 * The UI calls these and observes [CallStore]. The LiveKit media session is
 * not here: the UI connects with [CallState.token] once the phase is
 * CONNECTING or IN_CALL.
 */
object CallService {
    /** How long an outgoing call rings before it is reported as unanswered (web: 30 s). */
    const val OUTGOING_CALL_TIMEOUT_MS = 30_000L
    const val NO_ANSWER_MESSAGE = "No answer - call timed out"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Whether a call can start in [room] (web `canCall` and `isRoomAllowed`):
     * calls enabled, a LiveKit URL configured, and a room type that is allowed.
     */
    fun canCall(room: Room?, config: VideoCallsConfig?): Boolean {
        if (room == null || config == null) return false
        if (!config.enabled || config.livekitUrl.isBlank()) return false
        return room.type == RoomType.PRIVATE &&
            config.allowedRoomTypes.any { it.equals("private", ignoreCase = true) }
    }

    /** Audio calls are a separate opt-in on top of [canCall]. */
    fun canPlaceAudioCall(room: Room?, config: VideoCallsConfig?): Boolean =
        canCall(room, config) && config?.enableAudioCalls == true

    /**
     * Starts an outgoing call. Returns false when no call can start (not
     * allowed, a call is already active, or the room has no bare name).
     */
    fun placeCall(
        room: Room,
        kind: CallKind,
        client: XMPPClient?,
        config: VideoCallsConfig?
    ): Boolean {
        if (!canCall(room, config)) return false
        if (CallStore.snapshot().phase != CallPhase.IDLE) return false
        val chatName = room.jid.substringBefore("@")
        if (chatName.isBlank()) return false

        val selfLocal = selfXmppUsername().substringBefore("@")
        val peer = room.members.orEmpty().firstOrNull { member ->
            val local = member.xmppUsername.orEmpty().substringBefore("@")
            local.isNotEmpty() && local != selfLocal
        }
        val peerXmpp = peer?.xmppUsername?.takeIf { it.isNotBlank() }

        // Never show the server placeholder titles on the dial screen.
        val rawTitle = room.name.trim()
        val isBadTitle = rawTitle.isEmpty() ||
            rawTitle.lowercase() in setOf("deleted", "deleted user", "unknown", "null")
        val peerDisplay = peer?.let {
            listOf(it.firstName.orEmpty(), it.lastName.orEmpty())
                .joinToString(" ")
                .trim()
                .ifEmpty { it.name.orEmpty() }
        }.orEmpty()
        val dialName = (if (isBadTitle) peerDisplay else rawTitle)
            .ifEmpty { peerDisplay }
            .ifEmpty { chatName }

        CallStore.startOutgoingCall(
            roomJid = room.jid,
            roomName = dialName,
            roomBareName = chatName,
            kind = kind,
            peerXmppUsername = peerXmpp
        )
        scheduleNoAnswerTimeout(CallStore.snapshot().startedAt)

        // The server's call-token drops `kind`, so tell the peer directly first.
        // Direct chat is fast and normally lands before the relayed call-token.
        if (peerXmpp != null && client != null) {
            sendDirect(
                client = client,
                peer = peerXmpp,
                idPrefix = "call-invite",
                dataXml = CallStanzas.inviteData(kind, chatName, callId = null)
            )
        }

        // Own scope: the request must finish even if the caller's UI goes away.
        scope.launch {
            try {
                CallsAPIHelper.createChatCall(chatName, kind)
            } catch (e: Exception) {
                CallStore.setCallError(e.message ?: "Failed to create call")
            }
        }
        return true
    }

    /** Callee accepts the ringing call. The UI then connects with the token. */
    fun acceptIncoming() {
        CallStore.acceptIncomingCall()
    }

    /** Callee declines: the caller is told, and the local ring is dismissed. */
    fun declineIncoming(client: XMPPClient?) {
        sendStateSignal(client, "declined", CallStore.snapshot())
        CallStore.reset()
    }

    /**
     * Ends the active call from this side. A dial that has not been answered
     * is "cancelled", a connected call is "ended". Writes the local call-log row
     * and tells the peer so their UI dismisses.
     */
    fun hangUp(client: XMPPClient?, selfUsername: String = selfXmppUsername()) {
        val snap = CallStore.snapshot()
        if (snap.phase == CallPhase.IDLE) return
        val state = if (snap.phase == CallPhase.REQUESTING) "cancelled" else "ended"
        writeLocalLog(snap, selfUsername)
        sendStateSignal(client, state, snap)
        CallStore.reset()
    }

    /** The call connected to LiveKit. */
    fun markConnected() {
        CallStore.markInCall()
    }

    /** Joining the LiveKit room failed (or a fatal session error). */
    fun reportError(message: String) {
        CallStore.setCallError(message)
    }

    /** Dismiss an error card and return to idle. */
    fun dismissError() {
        CallStore.reset()
    }

    // ---- Helpers -------------------------------------------------------------

    private fun scheduleNoAnswerTimeout(startedAt: Long?) {
        scope.launch {
            delay(OUTGOING_CALL_TIMEOUT_MS)
            val snap = CallStore.snapshot()
            if (snap.phase == CallPhase.REQUESTING && snap.startedAt == startedAt) {
                CallStore.setCallError(NO_ANSWER_MESSAGE)
            }
        }
    }

    private fun sendStateSignal(client: XMPPClient?, state: String, snap: CallState) {
        val peer = snap.peerXmppUsername?.takeIf { it.isNotBlank() } ?: return
        if (client == null) return
        val room = snap.roomBareName?.takeIf { it.isNotBlank() }
            ?: snap.roomJid.orEmpty().substringBefore("@")
        sendDirect(
            client = client,
            peer = peer,
            idPrefix = "call-state",
            dataXml = CallStanzas.stateData(state, snap.callId, room)
        )
    }

    private fun sendDirect(client: XMPPClient, peer: String, idPrefix: String, dataXml: String) {
        // Best-effort: a dropped signal only delays the peer's UI update.
        val to = CallStanzas.peerJid(peer, client.xmppHost())
        val id = "$idPrefix-${System.currentTimeMillis()}"
        client.sendCallSignal(CallStanzas.directMessage(to, id, dataXml))
    }

    /**
     * Local call-log row, written before the state is reset. It is deduplicated
     * by callId against the server's copy, so both collapse into one row.
     */
    private fun writeLocalLog(snap: CallState, selfXmppUsername: String) {
        val roomJid = snap.roomJid ?: return
        if (snap.callId.isNullOrBlank() && snap.connectedAt == null) return
        val durationMs = snap.connectedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
        val log = CallLogs.buildLocal(
            callId = snap.callId.orEmpty(),
            direction = snap.direction ?: CallDirection.OUTGOING,
            durationMs = durationMs,
            roomJid = roomJid,
            selfXmppUsername = selfXmppUsername
        )
        MessageStore.addMessage(roomJid, log)
    }

    private fun selfXmppUsername(): String = UserStore.currentUser.value?.xmppUsername.orEmpty()
}
