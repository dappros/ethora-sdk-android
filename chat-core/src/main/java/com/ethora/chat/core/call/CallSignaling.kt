package com.ethora.chat.core.call

import com.ethora.chat.core.config.VideoCallsConfig
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.call.p2p.P2PRuntime
import com.ethora.chat.core.models.RoomMember
import com.ethora.chat.core.networking.CallsAPIHelper
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
/** How inbound signalling answers: sends a raw stanza, addressed with the session's XMPP [host]. */
class CallTransport(val host: String, val send: (String) -> Boolean)

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
        config: VideoCallsConfig? = ChatStore.getConfig()?.videoCalls,
        transport: CallTransport? = null
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
                handleToken(signal, inner, config, now, transport)
                true
            }
            is CallStanzas.Signal.P2p -> {
                P2PRuntime.onSignal(signal.signal, selfXmppUsername())
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
        now: Long,
        transport: CallTransport?
    ) {
        if (config?.enabled != true) return
        if (!CallStanzas.isFreshCallToken(inner, now)) return

        val token = signal.token
        val stanzaRoom = signal.room
        if (stanzaRoom.isEmpty()) return
        // A p2p token is intentionally empty (the media never touches LiveKit) and needs the
        // callId to correlate the signalling; a LiveKit token is useless without the JWT.
        if (signal.isP2p) {
            if (signal.callId.isNullOrBlank()) return
        } else if (token.isEmpty()) {
            return
        }
        // livekitUrl, ice and callerName steer where the media goes and who the ring screen
        // names, so they are honoured only from the app's system account (web senderTrust).
        // From anyone else the token still rings; the callee then uses the configured URL,
        // fetches its ICE servers from the API and works the name out locally.
        val trustedSender = CallStanzas.isSystemAccountSender(
            CallStanzas.fromAttribute(inner),
            runCatching { ChatStore.getEffectiveXmppSettings().host }.getOrNull()
        )
        val livekitUrl = signal.livekitUrl.takeIf { trustedSender }
        val signalCallerName = signal.callerName.takeIf { trustedSender }.orEmpty()
        val signalCaller = signal.caller.takeIf { trustedSender }
        val iceServers = signal.iceServers.takeIf { trustedSender }.orEmpty()

        // The server does not put `kind` on the broadcast, so the dialer's
        // call-invite hint wins; the attribute is only a fallback.
        val kind = consumeKindHint(listOf(stanzaRoom, signal.callId)) ?: CallKind.fromWire(signal.kind)

        val resolvedRoom = resolveRoom(stanzaRoom)
        val allowedTypes = config.allowedRoomTypes.map { it.lowercase() }
        val usersCnt = resolvedRoom?.usersCnt ?: 0
        val isOneToOneLike = usersCnt in 1..2
        val isPrivateAllowed = "private" in allowedTypes
        val roomType = resolvedRoom?.type
        if (signal.group) {
            // Group room call (always LiveKit): needs an explicit opt-in, and a room known to be private contradicts it.
            if ("group" !in allowedTypes) return
            if (roomType == RoomType.PRIVATE) return
        } else {
            if (roomType != null && roomType != RoomType.PRIVATE && !isOneToOneLike) return
            if (roomType == RoomType.PRIVATE && !isPrivateAllowed) return
            if (roomType == null && !stanzaRoom.contains("@") && !isOneToOneLike) return
        }

        val roomJid = resolvedRoom?.jid?.takeIf { it.isNotBlank() }
            ?: stanzaRoom.takeIf { it.contains("@") }
            ?: return

        // A p2p token has no JWT (no caller name in it); a LiveKit one names the caller in `sub`.
        val payload = if (signal.isP2p) null else CallStanzas.jwtPayload(token)
        val jwtName = if (signal.group) "" else CallStanzas.sanitizeCallName(CallStanzas.callerDisplayName(payload))
        val callId = signal.callId ?: CallStanzas.callIdFromJwt(payload)
        if (signal.isP2p && callId.isNullOrBlank()) return

        // Who the other person is: the room name for `<a>-<b>` chats, the trusted `caller`
        // attribute, else the other member of the room. A group call has no single peer.
        val peerMember = if (signal.group) null else otherMember(resolvedRoom)
        val peer = if (signal.group) {
            null
        } else {
            derivePeerXmppUsername(stanzaRoom, selfXmppUsername())
                ?: signalCaller
                ?: peerMember?.xmppUsername?.takeIf { it.isNotBlank() }
        }

        val resolvedTitle = resolvedRoom?.name?.trim().orEmpty()
        val titleLooksBad = CallStanzas.sanitizeCallName(resolvedTitle).isEmpty() ||
            resolvedTitle.lowercase() in PLACEHOLDER_TITLES
        val incomingRoomName = if (signal.group) {
            // The call is the room; the caller is carried separately.
            (if (titleLooksBad) "" else resolvedTitle).ifEmpty { CallStanzas.sanitizeCallName(stanzaRoom) }
        } else {
            // A person's name is preferred; a raw chat name (two joined JIDs) is never shown.
            signalCallerName
                .ifEmpty { jwtName }
                .ifEmpty { if (titleLooksBad) "" else resolvedTitle }
                .ifEmpty { CallStanzas.sanitizeCallName(memberDisplayName(peerMember)) }
                .ifEmpty { CallStanzas.sanitizeCallName(stanzaRoom) }
        }

        val snap = CallStore.snapshot()

        if (signal.isP2p) {
            ringP2p(
                callId = callId.orEmpty(),
                roomJid = roomJid,
                stanzaRoom = stanzaRoom,
                roomName = incomingRoomName,
                kind = kind,
                peer = peer,
                iceServers = iceServers,
                transport = transport,
                now = now
            )
            return
        }

        // A LiveKit token while a p2p call is active: one call at a time, the newcomer is ignored.
        if (snap.isP2p || snap.phase != CallPhase.IDLE && snap.mode == CallStanzas.MODE_P2P) {
            val decision = decideIncomingWhileBusy(
                BusyCallSnapshot(snap.phase, snap.direction, snap.mode, snap.callId, snap.roomBareName),
                signal.mode, callId, stanzaRoom
            )
            if (decision != IncomingWhileBusy.ACCEPT && decision != IncomingWhileBusy.REPLACE) return
        }

        val isOutgoingRoomMatch = snap.direction == CallDirection.OUTGOING && (
            snap.roomJid == roomJid ||
                snap.roomName == incomingRoomName ||
                snap.roomBareName == stanzaRoom
            )
        if (isOutgoingRoomMatch) {
            CallStore.setOutgoingCallToken(roomJid, token, callId, now, livekitUrl)
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
            now = now,
            livekitUrl = livekitUrl,
            isGroup = signal.group
        )
    }

    /**
     * Rings from a push notification's `data` payload (see [CallPush]); the host app's messaging
     * service calls this for a payload [CallPush.isCallPush] recognises. Returns true when a ring
     * was raised. A p2p call (empty `callToken`, `mode=p2p`) rings the same as a LiveKit one; its
     * ICE servers are fetched from the API when the callee accepts.
     */
    fun ringFromPush(
        data: Map<String, String?>,
        config: VideoCallsConfig? = ChatStore.getConfig()?.videoCalls,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (config?.enabled != true || !CallPush.isCallPush(data)) return false
        val parsed = CallPush.parse(data)
        val stanzaRoom = parsed.roomBareName ?: return false
        val callId = parsed.callId
        // A p2p push has no LiveKit token and needs the callId; a LiveKit one is useless without the token.
        if (parsed.isP2p) {
            if (callId.isNullOrBlank()) return false
        } else if (parsed.token.isNullOrBlank()) {
            return false
        }
        if (CallStore.snapshot().phase != CallPhase.IDLE) return false

        val resolvedRoom = resolveRoom(stanzaRoom)
        val allowedTypes = config.allowedRoomTypes.map { it.lowercase() }
        if (parsed.isGroup) {
            if ("group" !in allowedTypes || resolvedRoom?.type == RoomType.PRIVATE) return false
        } else if (resolvedRoom?.type == RoomType.PRIVATE && "private" !in allowedTypes) {
            return false
        }
        // A push names the room by its bare name; a freshly started app has not loaded its rooms yet.
        val roomJid = resolvedRoom?.jid?.takeIf { it.isNotBlank() }
            ?: parsed.roomRef?.takeIf { it.contains("@") }
            ?: runCatching { "$stanzaRoom@${ChatStore.getEffectiveConference()}" }.getOrNull()
            ?: return false

        val peerMember = if (parsed.isGroup) null else otherMember(resolvedRoom)
        val peer = if (parsed.isGroup) {
            null
        } else {
            parsed.callerXmppUsername
                ?: derivePeerXmppUsername(stanzaRoom, selfXmppUsername())
                ?: peerMember?.xmppUsername?.takeIf { it.isNotBlank() }
        }
        if (parsed.isP2p && peer == null) return false

        val resolvedTitle = resolvedRoom?.name?.trim().orEmpty()
        val titleLooksBad = CallStanzas.sanitizeCallName(resolvedTitle).isEmpty() ||
            resolvedTitle.lowercase() in PLACEHOLDER_TITLES
        val roomName = if (parsed.isGroup) {
            (if (titleLooksBad) "" else resolvedTitle).ifEmpty { CallStanzas.sanitizeCallName(stanzaRoom) }
        } else {
            parsed.callerName
                .ifEmpty { if (titleLooksBad) "" else resolvedTitle }
                .ifEmpty { CallStanzas.sanitizeCallName(memberDisplayName(peerMember)) }
        }

        CallStore.setIncomingCallToken(
            roomJid = roomJid,
            roomName = roomName,
            roomBareName = stanzaRoom,
            token = parsed.token.orEmpty(),
            kind = parsed.kind,
            callId = callId,
            peerXmppUsername = peer,
            now = now,
            isGroup = parsed.isGroup,
            mode = parsed.mode
        )
        if (parsed.isP2p) CallsAPIHelper.reportCallState(CallsAPIHelper.CallStateReport.RINGING, callId)
        return true
    }

    /**
     * An incoming `mode=p2p` call: one call at a time, decided by callId (web
     * `decideIncomingWhileBusy`). The ring never touches LiveKit; accepting starts the p2p session.
     */
    private fun ringP2p(
        callId: String,
        roomJid: String,
        stanzaRoom: String,
        roomName: String,
        kind: CallKind,
        peer: String?,
        iceServers: List<IceServerConfig>,
        transport: CallTransport?,
        now: Long
    ) {
        val snap = CallStore.snapshot()
        when (
            decideIncomingWhileBusy(
                BusyCallSnapshot(snap.phase, snap.direction, snap.mode, snap.callId, snap.roomBareName),
                CallStanzas.MODE_P2P, callId, stanzaRoom
            )
        ) {
            IncomingWhileBusy.DUPLICATE, IncomingWhileBusy.GLARE_KEEP -> return
            IncomingWhileBusy.BUSY -> {
                declineP2p(callId, peer, stanzaRoom, transport)
                return
            }
            IncomingWhileBusy.GLARE_YIELD -> {
                // We both dialled at once and the other call has the smaller id: drop ours
                // (the other side keeps theirs and ignores our token) and ring for theirs.
                sendState(transport, snap.peerXmppUsername, "cancelled", snap.callId, snap.roomBareName)
                CallsAPIHelper.reportCallState(CallsAPIHelper.CallStateReport.END, snap.callId)
                CallStore.reset()
            }
            IncomingWhileBusy.ACCEPT, IncomingWhileBusy.REPLACE -> Unit
        }
        if (peer == null) {
            // Without the peer's address there is nobody to signal: tell the server we cannot take it.
            CallsAPIHelper.reportCallState(CallsAPIHelper.CallStateReport.DECLINE, callId)
            return
        }
        CallStore.setIncomingCallToken(
            roomJid = roomJid,
            roomName = roomName,
            roomBareName = stanzaRoom,
            token = "",
            kind = kind,
            callId = callId,
            peerXmppUsername = peer,
            now = now,
            mode = CallStanzas.MODE_P2P,
            iceServers = iceServers
        )
        CallsAPIHelper.reportCallState(CallsAPIHelper.CallStateReport.RINGING, callId)
    }

    private fun declineP2p(callId: String, peer: String?, stanzaRoom: String, transport: CallTransport?) {
        CallsAPIHelper.reportCallState(CallsAPIHelper.CallStateReport.DECLINE, callId)
        sendState(transport, peer, "declined", callId, stanzaRoom)
    }

    /** Best-effort direct `call-state` signal to [peer]; a dropped one only delays the peer's UI. */
    private fun sendState(transport: CallTransport?, peer: String?, state: String, callId: String?, room: String?) {
        val target = peer?.takeIf { it.isNotBlank() } ?: return
        if (transport == null) return
        val to = CallStanzas.peerJid(target, transport.host)
        val id = "call-state-${System.currentTimeMillis()}"
        transport.send(CallStanzas.directMessage(to, id, CallStanzas.stateData(state, callId, room)))
    }

    private fun otherMember(room: Room?): RoomMember? {
        val self = selfXmppUsername().substringBefore("@")
        return room?.members.orEmpty().firstOrNull { member ->
            val local = member.xmppUsername.orEmpty().substringBefore("@")
            local.isNotEmpty() && local != self
        }
    }

    private fun memberDisplayName(member: RoomMember?): String {
        if (member == null) return ""
        return listOf(member.firstName.orEmpty(), member.lastName.orEmpty())
            .joinToString(" ").trim().ifEmpty { member.name.orEmpty() }
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
            // A signal naming a different call than the one on screen (e.g. the log of the
            // previous call in the same room arriving late) must not tear the current one down.
            val callIdConflicts = !signal.callId.isNullOrBlank() &&
                !snap.callId.isNullOrBlank() && snap.callId != signal.callId
            val declinedInGroup = signal.state == "declined" && signal.group
            val isTerminal = signal.state in CallStanzas.TERMINAL_STATES && !declinedInGroup
            val isServerCallLog = signal.state.isEmpty() && signal.hasLogData
            if ((callIdMatches || (roomMatches && !callIdConflicts)) && (isTerminal || isServerCallLog)) {
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
