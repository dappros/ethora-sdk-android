package com.ethora.chat.core.call

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * How a call is carried, shared by the dialer, the stanza router and the overlay.
 * Port of web `calls/callMode.ts`.
 *
 * Three kinds of call exist:
 *  - 1:1 over our own WebRTC peer connection (`p2p`): chosen by the server, only for
 *    private chats and only when the install has P2P enabled;
 *  - 1:1 over LiveKit: the fallback when the server answers `livekit`;
 *  - group over LiveKit: any non-private chat, always `livekit`.
 * The client never picks the transport, it follows the server's `mode`.
 */

/** One STUN/TURN entry (serializable subset of a WebRTC ICE server). */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

private val ICE_URL = Regex("^(stun|stuns|turn|turns):", RegexOption.IGNORE_CASE)

/**
 * Accepts an array, an `{ iceServers }` envelope or a JSON string of either (the
 * call-token stanza carries the list as a JSON attribute) and returns only well
 * formed stun/turn entries. Anything else is dropped, never thrown.
 */
fun normalizeIceServers(raw: JsonElement?): List<IceServerConfig> {
    var value: JsonElement? = raw
    if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        val text = value.asString.trim()
        if (text.isEmpty()) return emptyList()
        value = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            return emptyList()
        }
    }
    if (value != null && value.isJsonObject && value.asJsonObject.get("iceServers")?.isJsonArray == true) {
        value = value.asJsonObject.get("iceServers")
    }
    if (value == null || !value.isJsonArray) return emptyList()

    val out = mutableListOf<IceServerConfig>()
    for (entry in value.asJsonArray) {
        if (!entry.isJsonObject) continue
        val e = entry.asJsonObject
        val rawUrls = e.get("urls") ?: e.get("url")
        val candidates: List<JsonElement> = when {
            rawUrls == null || rawUrls.isJsonNull -> emptyList()
            rawUrls.isJsonArray -> rawUrls.asJsonArray.toList()
            else -> listOf(rawUrls)
        }
        val urls = candidates
            .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            .map { it.asString.trim() }
            .filter { ICE_URL.containsMatchIn(it) }
        if (urls.isEmpty()) continue
        out += IceServerConfig(
            urls = urls,
            username = e.stringOrNull("username"),
            credential = e.stringOrNull("credential")
        )
    }
    return out
}

fun normalizeIceServers(raw: String?): List<IceServerConfig> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return try {
        normalizeIceServers(JsonParser.parseString(text))
    } catch (_: Exception) {
        emptyList()
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotEmpty() }

/** Normalised `POST /v1/chats/call/create/:chatName` response. */
data class CallCreateResult(
    val callId: String?,
    /** `p2p` or `livekit`. */
    val mode: String,
    val kind: CallKind,
    /** Server-reported group flag, null when the server did not say. */
    val group: Boolean?,
    /** Public LiveKit URL when the server knows it. */
    val livekitUrl: String?,
    val iceServers: List<IceServerConfig>,
    val ttlSeconds: Long?
) {
    val isP2p: Boolean get() = mode == CallStanzas.MODE_P2P
}

/**
 * Reads the create response. Old servers answer with no `mode`, which maps to `livekit`
 * (the caller then waits for its own call-token as before). A p2p answer without a callId
 * is unusable (no way to correlate signalling) and throws.
 */
fun parseCallCreateResponse(data: JsonObject?, fallbackKind: CallKind): CallCreateResult {
    val root = data ?: JsonObject()
    val result = root.get("result")
    val d = if (result != null && result.isJsonObject && root.get("mode") == null) result.asJsonObject else root
    val mode = if (d.stringOrNull("mode")?.trim()?.equals(CallStanzas.MODE_P2P, ignoreCase = true) == true) {
        CallStanzas.MODE_P2P
    } else {
        CallStanzas.MODE_LIVEKIT
    }
    val kindRaw = d.stringOrNull("kind")?.trim()?.lowercase()
    val callId = (d.stringOrNull("callId") ?: d.stringOrNull("callid"))?.trim()?.takeIf { it.isNotEmpty() }
    if (mode == CallStanzas.MODE_P2P && callId == null) {
        throw IllegalStateException("Call response is missing callId")
    }
    val ttl = d.get("ttlSeconds")?.takeIf { it.isJsonPrimitive }?.let {
        try { it.asDouble } catch (_: Exception) { null }
    }
    val group = d.get("group")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    return CallCreateResult(
        callId = callId,
        mode = mode,
        kind = if (kindRaw == "audio" || kindRaw == "video") CallKind.fromWire(kindRaw) else fallbackKind,
        group = group,
        livekitUrl = CallStanzas.normalizeLivekitUrl(d.stringOrNull("livekitUrl")),
        iceServers = if (mode == CallStanzas.MODE_P2P) normalizeIceServers(d.get("iceServers")) else emptyList(),
        ttlSeconds = ttl?.takeIf { it.isFinite() && it > 0 }?.toLong()
    )
}

/** What the call-token handler should do with an incoming token while another call may be active. */
enum class IncomingWhileBusy {
    /** Nothing active: ring. */
    ACCEPT,
    /** Same callId as the active call (server re-delivery): ignore. */
    DUPLICATE,
    /** Legacy LiveKit behaviour: the new token overwrites the old call. */
    REPLACE,
    /** Both sides dialled each other and the smaller callId wins on both ends; this is the loser: drop ours, ring for theirs. */
    GLARE_YIELD,
    /** Same race, we hold the winning callId: ignore theirs (the other side yields). */
    GLARE_KEEP,
    /** A different call is in progress: decline the newcomer. */
    BUSY
}

/** The part of the active call [decideIncomingWhileBusy] looks at. */
data class BusyCallSnapshot(
    val phase: CallPhase,
    val direction: CallDirection?,
    val mode: String,
    val callId: String?,
    val roomBareName: String?
)

fun decideIncomingWhileBusy(
    current: BusyCallSnapshot,
    incomingMode: String,
    incomingCallId: String?,
    incomingRoomBareName: String?
): IncomingWhileBusy {
    if (current.phase == CallPhase.IDLE) return IncomingWhileBusy.ACCEPT
    if (!incomingCallId.isNullOrEmpty() && current.callId == incomingCallId) return IncomingWhileBusy.DUPLICATE
    val currentP2p = current.mode == CallStanzas.MODE_P2P
    val incomingP2p = incomingMode == CallStanzas.MODE_P2P
    if (!currentP2p && !incomingP2p) return IncomingWhileBusy.REPLACE

    val sameRoom = !incomingRoomBareName.isNullOrEmpty() && current.roomBareName == incomingRoomBareName
    if (sameRoom &&
        current.direction == CallDirection.OUTGOING &&
        current.phase == CallPhase.REQUESTING &&
        incomingP2p && currentP2p &&
        !current.callId.isNullOrEmpty() && !incomingCallId.isNullOrEmpty()
    ) {
        return if (incomingCallId < current.callId) IncomingWhileBusy.GLARE_YIELD else IncomingWhileBusy.GLARE_KEEP
    }
    return IncomingWhileBusy.BUSY
}
