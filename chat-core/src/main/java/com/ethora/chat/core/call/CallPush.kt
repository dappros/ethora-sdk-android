package com.ethora.chat.core.call

/**
 * Recognise and parse an incoming-call push notification (web `helpers/callPush.ts`).
 *
 * Calls normally ring through a live XMPP `call-token` stanza, which only arrives while the
 * socket is up. When the app is backgrounded the backend also delivers the call over push, in
 * the same `data` format as message pushes. The host app's messaging service passes that data
 * to [CallSignaling.ringFromPush]. A p2p call carries an EMPTY `callToken` and `mode=p2p`.
 */
data class ParsedCallPush(
    val callId: String?,
    /** LiveKit token, if the push carried one. Empty for a p2p call. */
    val token: String?,
    val kind: CallKind,
    /** Bare room name (JID localpart) the call belongs to. */
    val roomBareName: String?,
    /** Raw room reference from the payload (full JID or bare name). */
    val roomRef: String?,
    /** Display name for the ring screen (caller), already sanitised. */
    val callerName: String,
    /** Caller's xmpp localpart, when provided. */
    val callerXmppUsername: String?,
    /** The call is for a group room (always LiveKit). */
    val isGroup: Boolean,
    /** `p2p` or `livekit` (default, also for pushes that predate the field). */
    val mode: String
) {
    val isP2p: Boolean get() = mode == CallStanzas.MODE_P2P
}

object CallPush {
    private val CALL_MARKERS = setOf("call", "incoming-call", "call-token", "call-invite")

    private fun pick(data: Map<String, String?>, keys: List<String>): String {
        for (key in keys) {
            val value = data[key]?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return ""
    }

    /**
     * True when a push `data` payload represents an incoming call. Matches on an explicit call
     * marker or on a callId paired with a call-specific field, so a normal message push is
     * never misread as a call.
     */
    fun isCallPush(data: Map<String, String?>?): Boolean {
        if (data == null) return false
        val marker = pick(data, listOf("type", "pushType", "category", "messageType", "event")).lowercase()
        if (marker in CALL_MARKERS) return true
        val callId = pick(data, listOf("callId", "callid", "callID"))
        val kind = pick(data, listOf("kind")).lowercase()
        val hasCallField = pick(data, listOf("callToken", "livekitToken")).isNotEmpty() ||
            pick(data, listOf("callKind")).isNotEmpty() ||
            kind == "audio" || kind == "video" ||
            pick(data, listOf("mode")).lowercase() == CallStanzas.MODE_P2P
        return callId.isNotEmpty() && hasCallField
    }

    fun parse(data: Map<String, String?>?): ParsedCallPush {
        val d = data.orEmpty()
        val roomRef = pick(d, listOf("callRoom", "room", "roomJid", "jid", "chatId")).takeIf { it.isNotEmpty() }
        val kindRaw = pick(d, listOf("callKind", "kind")).lowercase()
        val mode = if (pick(d, listOf("mode")).equals(CallStanzas.MODE_P2P, ignoreCase = true)) {
            CallStanzas.MODE_P2P
        } else {
            CallStanzas.MODE_LIVEKIT
        }
        return ParsedCallPush(
            callId = pick(d, listOf("callId", "callid", "callID")).takeIf { it.isNotEmpty() },
            token = pick(d, listOf("callToken", "livekitToken", "token")).takeIf { it.isNotEmpty() },
            kind = if (kindRaw == "audio") CallKind.AUDIO else CallKind.VIDEO,
            roomBareName = roomRef?.substringBefore("@")?.ifEmpty { roomRef },
            roomRef = roomRef,
            // Push text is untrusted: sanitised, and never a raw chat name.
            callerName = CallStanzas.sanitizeCallName(
                pick(d, listOf("callerName", "callerDisplayName", "senderName", "title"))
            ),
            callerXmppUsername = CallStanzas.sanitizeXmppUsername(
                pick(d, listOf("callerXmppUsername", "callerUsername", "userJid", "senderJid")).substringBefore("@")
            ),
            isGroup = mode == CallStanzas.MODE_LIVEKIT &&
                pick(d, listOf("group", "isGroup")).lowercase().let { it == "true" || it == "1" },
            mode = mode
        )
    }
}

/** Texts of the incoming-call notification, built from a push payload. */
object CallPushText {
    fun title(parsed: ParsedCallPush): String {
        val what = when {
            parsed.isGroup -> if (parsed.kind == CallKind.AUDIO) "Incoming group audio call" else "Incoming group video call"
            parsed.kind == CallKind.AUDIO -> "Incoming audio call"
            else -> "Incoming video call"
        }
        return what
    }

    /** The caller's name, or a neutral label (never a raw chat name). */
    fun body(parsed: ParsedCallPush): String = parsed.callerName.ifBlank { "Unknown caller" }
}
