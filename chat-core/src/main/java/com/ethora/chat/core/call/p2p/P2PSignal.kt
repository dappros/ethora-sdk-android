package com.ethora.chat.core.call.p2p

import com.ethora.chat.core.call.CallStanzas
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire format of the peer to peer call signalling (web `calls/p2p/signalStanza.ts`).
 * It travels as a direct XMPP chat message between the two users, no server involvement:
 *
 *     <message type="chat" to="<peer bare jid>">
 *       <body>system</body>
 *       <data type="call-p2p" callId="..." signal="offer|answer|candidate|end"
 *             payload="<JSON string>"/>
 *     </message>
 *
 * `payload` is the SDP ({type, sdp}) for offer/answer, the ICE candidate for `candidate`
 * and `{reason}` for `end`. Pure functions only (no Android, no network).
 */
enum class P2PSignalName(val wire: String) {
    OFFER("offer"),
    ANSWER("answer"),
    CANDIDATE("candidate"),
    END("end");

    companion object {
        fun fromWire(value: String): P2PSignalName? = entries.firstOrNull { it.wire == value }
    }
}

sealed interface P2PPayload {
    /** SDP of an offer or an answer. */
    data class Sdp(val type: String, val sdp: String) : P2PPayload

    data class Candidate(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
        val usernameFragment: String? = null
    ) : P2PPayload

    data class End(val reason: String? = null) : P2PPayload
}

data class P2PSignal(
    val callId: String,
    val signal: P2PSignalName,
    val payload: P2PPayload,
    /** Sender's bare JID as written in `from` (lower-cased), "" when missing. */
    val fromBare: String,
    /** Sender's xmpp localpart (the part before `@`), "" when missing. */
    val fromLocal: String,
    /** Age of the stanza from the server's stanza-id timestamp, null when it carries none. */
    val ageMs: Long?,
    /** Carries `<delay/>`: it was stored offline and replayed, so it is stale. */
    val delayed: Boolean
)

object P2PSignalStanza {
    const val TYPE = "call-p2p"

    /**
     * A signal older than this (by the server's own timestamp) is not part of a live
     * negotiation. Call-tokens use 5 s; signalling gets more headroom because candidates
     * can queue behind a slow permission prompt on the sender, but anything the server
     * replays after a reconnect is far older.
     */
    const val MAX_SIGNAL_AGE_MS = 15_000L

    // SDP for an audio + video call is a few KB; this only guards against a hostile peer
    // pushing megabytes through an attribute.
    private const val MAX_SDP_LENGTH = 64 * 1024
    private const val MAX_CANDIDATE_LENGTH = 2048
    private const val MAX_REASON_LENGTH = 64

    // The payload attribute may contain `>` once a server re-serialises it, so the start
    // tag is matched with quoted values skipped over.
    private val DATA_TAG = Regex("<data\\b(?:\"[^\"]*\"|'[^']*'|[^>\"'])*>")
    private val STANZA_ID_TAG = Regex("<stanza-id\\b[^>]*>")
    private val DELAY_TAG = Regex("<delay\\b")
    private val LONG_DIGITS = Regex("\\d{13,}")

    private var outgoingCounter = 0

    @Synchronized
    private fun nextCounter(): Int {
        outgoingCounter = (outgoingCounter + 1) % 1_000_000
        return outgoingCounter
    }

    fun payloadJson(payload: P2PPayload): String {
        val json = JsonObject()
        when (payload) {
            is P2PPayload.Sdp -> {
                json.addProperty("type", payload.type)
                json.addProperty("sdp", payload.sdp)
            }
            is P2PPayload.Candidate -> {
                json.addProperty("candidate", payload.candidate)
                json.addProperty("sdpMid", payload.sdpMid)
                json.addProperty("sdpMLineIndex", payload.sdpMLineIndex)
                json.addProperty("usernameFragment", payload.usernameFragment)
            }
            is P2PPayload.End -> payload.reason?.let { json.addProperty("reason", it) }
        }
        return json.toString()
    }

    /** The signal as a complete `<message>` stanza for [to] (a bare JID). */
    fun build(
        to: String,
        callId: String,
        signal: P2PSignalName,
        payload: P2PPayload,
        now: Long = System.currentTimeMillis()
    ): String {
        val id = "call-p2p-$now-${nextCounter()}"
        // Signalling is only meaningful live: keep it out of the archive and the offline
        // store (a replayed offer for a finished call is just noise).
        return "<message to=\"${CallStanzas.escape(to)}\" type=\"chat\" id=\"$id\">" +
            "<body>system</body>" +
            "<data type=\"$TYPE\" callId=\"${CallStanzas.escape(callId)}\" signal=\"${signal.wire}\" " +
            "payload=\"${CallStanzas.escape(payloadJson(payload))}\"/>" +
            "<no-store xmlns=\"urn:xmpp:hints\"/><no-permanent-store xmlns=\"urn:xmpp:hints\"/>" +
            "</message>"
    }

    private fun validatePayload(signal: P2PSignalName, raw: JsonObject): P2PPayload? {
        fun str(key: String): String? =
            raw.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return when (signal) {
            P2PSignalName.OFFER, P2PSignalName.ANSWER -> {
                if (str("type") != signal.wire) return null
                val sdp = str("sdp")
                if (sdp.isNullOrEmpty() || sdp.length > MAX_SDP_LENGTH) return null
                P2PPayload.Sdp(signal.wire, sdp)
            }
            P2PSignalName.CANDIDATE -> {
                val candidate = str("candidate") ?: return null
                if (candidate.length > MAX_CANDIDATE_LENGTH) return null
                val index = raw.get("sdpMLineIndex")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                P2PPayload.Candidate(candidate, str("sdpMid"), index, str("usernameFragment"))
            }
            P2PSignalName.END -> P2PPayload.End(str("reason")?.take(MAX_REASON_LENGTH))
        }
    }

    // Same trick as the call-token freshness check: the server stamps a microsecond id on
    // archived stanzas and the first 13 digits are epoch ms.
    private fun stanzaAgeMs(xml: String, now: Long): Long? {
        val tag = STANZA_ID_TAG.find(xml)?.value ?: return null
        val id = CallStanzas.attributesOf(tag, STANZA_ID_TAG)?.get("id")?.trim().orEmpty()
        val digits = LONG_DIGITS.find(id)?.value ?: return null
        val ts = digits.take(13).toLongOrNull() ?: return null
        return if (ts > 0) kotlin.math.abs(now - ts) else null
    }

    /**
     * Reads a `call-p2p` message ([innerMessageXml] is an already unwrapped `<message>`).
     * Returns null for anything that is not a well formed signal (wrong type, unknown
     * signal, bad JSON, bad payload).
     */
    fun parse(innerMessageXml: String, now: Long = System.currentTimeMillis()): P2PSignal? {
        val attrs = CallStanzas.attributesOf(innerMessageXml, DATA_TAG) ?: return null
        if (!attrs["type"].orEmpty().trim().equals(TYPE, ignoreCase = true)) return null
        val callId = (attrs["callId"] ?: attrs["callid"]).orEmpty().trim()
        val name = P2PSignalName.fromWire(attrs["signal"].orEmpty().trim().lowercase())
        if (callId.isEmpty() || name == null) return null

        val json = try {
            JsonParser.parseString(attrs["payload"].takeUnless { it.isNullOrEmpty() } ?: "{}")
                .takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val payload = validatePayload(name, json) ?: return null

        val fromBare = (CallStanzas.fromAttribute(innerMessageXml).orEmpty())
            .substringBefore("/").trim().lowercase()
        return P2PSignal(
            callId = callId,
            signal = name,
            payload = payload,
            fromBare = fromBare,
            fromLocal = fromBare.substringBefore("@"),
            ageMs = stanzaAgeMs(innerMessageXml, now),
            delayed = DELAY_TAG.containsMatchIn(innerMessageXml)
        )
    }

    /**
     * Whether a parsed signal belongs to a live negotiation. An `end` is always accepted
     * unless replayed (it only ever acts on a call that is still active, and stopping a
     * call late is better than never).
     */
    fun isFresh(signal: P2PSignal): Boolean {
        if (signal.delayed) return false
        if (signal.signal == P2PSignalName.END) return true
        return signal.ageMs == null || signal.ageMs <= MAX_SIGNAL_AGE_MS
    }
}
