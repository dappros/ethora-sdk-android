package com.ethora.chat.core.call

import com.ethora.chat.core.config.VideoCallsConfig
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.extractBody
import com.ethora.chat.core.xmpp.unwrapMucSubInnerMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Inbound call signalling. Port of web `onCallTokenMessage`
 * (networking/callTokenStanza.ts): call stanzas are consumed here, before the
 * chat pipeline, so they never render as messages.
 *
 * Also owns the call-log rows (live and archived) and the call-invite kind
 * hints. Outbound stanzas and the REST call are in [CallService].
 */
object CallSignaling {
    private const val KIND_HINT_TTL_MS = 60_000L

    /** Legacy servers broadcast the signal as plain body text. Never shown as chat. */
    private val CALL_BODY_KEYWORDS = setOf(
        "call-token", "call-state", "call-ringing", "call-ended", "call-declined",
        "call-cancelled", "call-canceled", "call-timeout", "call-rejected"
    )

    private val PLACEHOLDER_TITLES = setOf("deleted", "deleted user", "unknown", "null")

    /** Key (room or callId) to the kind hint and its expiry time. */
    private val kindHints = ConcurrentHashMap<String, Pair<CallKind, Long>>()

    /**
     * Handles one live inbound `<message>` stanza.
     *
     * Returns true when the stanza was call signalling and must not reach the
     * chat pipeline (including a call-token that was stale or ignored). Returns
     * false for everything else. Never call this for MAM archive results: use
     * [archivedCallLog] for those.
     */
    fun handleInbound(
        rawXml: String,
        now: Long = System.currentTimeMillis(),
        config: VideoCallsConfig? = ChatStore.getConfig()?.videoCalls
    ): Boolean {
        if (!rawXml.contains("call-")) return false
        val outerFrom = CallStanzas.fromAttribute(rawXml)
        val inner = unwrapMucSubInnerMessage(rawXml)
        val signal = CallStanzas.parseSignal(inner)
            ?: return handleBodyKeyword(inner, now)

        return when (signal) {
            is CallStanzas.Signal.State -> {
                handleState(signal, outerFrom, now)
                true
            }
            is CallStanzas.Signal.Invite -> {
                handleInvite(signal)
                true
            }
            is CallStanzas.Signal.Token -> {
                handleToken(signal, inner, config, now)
                true
            }
            is CallStanzas.Signal.Other -> true
        }
    }

    /**
     * Call-log row for an archived (MAM) `<message>`, or null when the archive
     * entry is not a call-state log. Other archived call-* payloads are not chat
     * rows either, so callers should drop them when this returns null.
     */
    fun archivedCallLog(
        innerMessageXml: String,
        roomJid: String,
        timestamp: Long
    ): Message? {
        val signal = CallStanzas.parseSignal(innerMessageXml) as? CallStanzas.Signal.State ?: return null
        if (!signal.hasLogData) return null
        return CallLogs.buildFromServer(
            callId = signal.callId,
            callerXmppUsername = signal.callerXmppUsername,
            durationMs = signal.durationMs,
            roomJid = roomJid,
            selfXmppUsername = selfXmppUsername(),
            now = timestamp
        )
    }

    // ---- Kind hints ----------------------------------------------------------

    /** Stashes the kind for a room or callId until the call-token arrives. */
    internal fun stashKindHint(key: String?, kind: CallKind, now: Long = System.currentTimeMillis()) {
        if (key.isNullOrBlank()) return
        kindHints.entries.removeIf { it.value.second < now }
        kindHints[key] = kind to (now + KIND_HINT_TTL_MS)
    }

    internal fun consumeKindHint(
        keys: List<String?>,
        now: Long = System.currentTimeMillis()
    ): CallKind? {
        for (key in keys) {
            if (key.isNullOrBlank()) continue
            val hit = kindHints[key] ?: continue
            if (hit.second >= now) {
                kindHints.remove(key)
                return hit.first
            }
        }
        return null
    }

    internal fun clearKindHints() = kindHints.clear()

    // ---- Handlers ------------------------------------------------------------

    private fun handleBodyKeyword(inner: String, now: Long): Boolean {
        val body = extractBody(inner)?.trim()?.lowercase().orEmpty()
        if (body.isEmpty() || body !in CALL_BODY_KEYWORDS) return false
        // Body-only variant: a state notification. Terminal states tear the call down.
        handleState(
            CallStanzas.Signal.State(
                state = body.removePrefix("call-"),
                room = null,
                callId = null,
                callerXmppUsername = null,
                durationMs = null,
                kind = null,
                hasLogData = false
            ),
            outerFrom = null,
            now = now
        )
        return true
    }

    private fun handleInvite(signal: CallStanzas.Signal.Invite) {
        stashKindHint(signal.room, signal.kind)
        stashKindHint(signal.callId, signal.kind)

        val snap = CallStore.snapshot()
        if (snap.phase == CallPhase.IDLE) return
        val roomMatches = !signal.room.isNullOrBlank() && (
            snap.roomBareName == signal.room ||
                snap.roomJid == signal.room ||
                snap.roomJid.orEmpty().substringBefore("@") == signal.room
            )
        val callIdMatches = !signal.callId.isNullOrBlank() && snap.callId == signal.callId
        if ((roomMatches || callIdMatches) && snap.kind != signal.kind) {
            CallStore.setCallKind(signal.kind)
        }
    }

    private fun handleToken(
        signal: CallStanzas.Signal.Token,
        inner: String,
        config: VideoCallsConfig?,
        now: Long
    ) {
        if (config?.enabled != true) return
        if (!CallStanzas.isFreshCallToken(inner, now)) return

        val token = signal.token
        val stanzaRoom = signal.room
        if (token.isEmpty() || stanzaRoom.isEmpty()) return

        // The server does not put `kind` on the broadcast, so the dialer's
        // call-invite hint wins; the attribute is only a fallback.
        val kind = consumeKindHint(listOf(stanzaRoom, signal.callId)) ?: CallKind.fromWire(signal.kind)

        val resolvedRoom = resolveRoom(stanzaRoom)
        val allowedTypes = config.allowedRoomTypes.map { it.lowercase() }
        val usersCnt = resolvedRoom?.usersCnt ?: 0
        val isOneToOneLike = usersCnt in 1..2
        val isPrivateAllowed = "private" in allowedTypes
        val roomType = resolvedRoom?.type
        if (roomType != null && roomType != RoomType.PRIVATE && !isOneToOneLike) return
        if (roomType == RoomType.PRIVATE && !isPrivateAllowed) return
        if (roomType == null && !stanzaRoom.contains("@") && !isOneToOneLike) return

        val roomJid = resolvedRoom?.jid?.takeIf { it.isNotBlank() }
            ?: stanzaRoom.takeIf { it.contains("@") }
            ?: return

        // Prefer the JWT `sub` (the caller's full name) over a placeholder room title.
        val payload = CallStanzas.jwtPayload(token)
        val callerName = CallStanzas.callerDisplayName(payload)
        val resolvedTitle = resolvedRoom?.name?.trim().orEmpty()
        val titleLooksBad = resolvedTitle.isEmpty() || resolvedTitle.lowercase() in PLACEHOLDER_TITLES
        val incomingRoomName = (if (callerName.isNotEmpty() && titleLooksBad) callerName else resolvedTitle)
            .ifEmpty { callerName }
            .ifEmpty { stanzaRoom }

        val callId = signal.callId ?: CallStanzas.callIdFromJwt(payload)
        val peer = derivePeerXmppUsername(stanzaRoom, selfXmppUsername())

        val snap = CallStore.snapshot()
        val isOutgoingRoomMatch = snap.direction == CallDirection.OUTGOING && (
            snap.roomJid == roomJid ||
                snap.roomName == incomingRoomName ||
                snap.roomBareName == stanzaRoom
            )
        if (isOutgoingRoomMatch) {
            CallStore.setOutgoingCallToken(roomJid, token, callId, now)
            return
        }
        CallStore.setIncomingCallToken(
            roomJid = roomJid,
            roomName = incomingRoomName,
            roomBareName = stanzaRoom,
            token = token,
            kind = kind,
            callId = callId,
            peerXmppUsername = peer,
            now = now
        )
    }

    /**
     * Call state. Tears the active call down on a terminal state, or on the
     * server call log (a 1:1 call's log means the other side left). Server call
     * logs also become a row in the room.
     */
    private fun handleState(
        signal: CallStanzas.Signal.State,
        outerFrom: String?,
        now: Long
    ) {
        val fromRoom = outerFrom.orEmpty().substringBefore("/").substringBefore("@").trim()
        val stanzaRoom = signal.room ?: fromRoom

        val snap = CallStore.snapshot()
        if (snap.phase != CallPhase.IDLE) {
            val roomMatches = stanzaRoom.isNotEmpty() && (
                snap.roomJid == stanzaRoom ||
                    snap.roomName == stanzaRoom ||
                    snap.roomBareName == stanzaRoom ||
                    snap.roomJid.orEmpty().substringBefore("@") == stanzaRoom
                )
            val callIdMatches = !signal.callId.isNullOrBlank() &&
                !snap.callId.isNullOrBlank() && snap.callId == signal.callId
            val isTerminal = signal.state in CallStanzas.TERMINAL_STATES
            val isServerCallLog = signal.state.isEmpty() && signal.hasLogData
            if ((roomMatches || callIdMatches) && (isTerminal || isServerCallLog)) {
                CallStore.reset()
            }
        }

        if (signal.hasLogData) {
            val roomJid = resolveRoom(stanzaRoom)?.jid ?: return
            val log = CallLogs.buildFromServer(
                callId = signal.callId,
                callerXmppUsername = signal.callerXmppUsername,
                durationMs = signal.durationMs,
                roomJid = roomJid,
                selfXmppUsername = selfXmppUsername(),
                now = now
            )
            MessageStore.addMessage(roomJid, log)
        }
    }

    // ---- Helpers -------------------------------------------------------------

    private fun selfXmppUsername(): String =
        UserStore.currentUser.value?.xmppUsername.orEmpty()

    /** Matches by full JID, then JID localpart, then display title (web lookup order). */
    private fun resolveRoom(roomAttr: String): Room? {
        if (roomAttr.isBlank()) return null
        val rooms = RoomStore.rooms.value
        return rooms.firstOrNull { it.jid == roomAttr }
            ?: rooms.firstOrNull { it.jid.substringBefore("@") == roomAttr }
            ?: rooms.firstOrNull { it.name == roomAttr }
    }

    /** `<userA>-<userB>` room name: the peer is the part that is not us. */
    internal fun derivePeerXmppUsername(bareRoomName: String, selfXmppUsername: String): String? {
        if (!bareRoomName.contains("-")) return null
        val parts = bareRoomName.split("-")
        if (parts.size != 2) return null
        val selfLocal = selfXmppUsername.substringBefore("@")
        return parts.firstOrNull { it.isNotEmpty() && it != selfLocal }
    }
}
