package com.ethora.chat.core.call

import com.ethora.chat.core.call.p2p.P2PSignal
import com.ethora.chat.core.call.p2p.P2PSignalStanza
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire format of the call signalling stanzas. Pure functions only (no Android
 * or network), so every shape is covered by JVM round-trip tests.
 *
 * Mirrors web `networking/callTokenStanza.ts`:
 *  - `<data type="call-token" token room kind callId/>` (server broadcast,
 *    MUC-SUB wrapped, fresh only: stanza-id timestamp within 5 s)
 *  - `<data type="call-invite" kind room callId/>` (direct chat, dialer to peer)
 *  - `<data type="call-state" state room callId/>` (direct chat, hang up,
 *    decline or cancel) and the server call log `<data type="call-state"
 *    callId durationMs callerXmppUsername/>` (carries log metadata)
 */
internal object CallStanzas {
    /** Maximum age of a call-token stanza-id timestamp. Older tokens are ignored. */
    const val MAX_CALL_TOKEN_AGE_MS = 5_000L

    private val DATA_TAG = Regex("<data\\b[^>]*>")
    private val STANZA_ID_TAG = Regex("<stanza-id\\b[^>]*>")
    private val MESSAGE_TAG = Regex("<message\\b[^>]*>")
    private val START_TAG_ATTR = Regex("([A-Za-z_][\\w:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val RAW_ID = Regex("^[0-9a-f_-]{16,}$", RegexOption.IGNORE_CASE)

    /** Terminal call-state values (web handleCallStateData). */
    val TERMINAL_STATES = setOf("ended", "declined", "cancelled", "canceled", "timeout", "rejected")

    // ---- Builders -----------------------------------------------------------

    fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /** Bare peer localpart to a full JID on [host]. Values that already have `@` pass through. */
    fun peerJid(peer: String, host: String): String =
        if (peer.contains("@")) peer else "$peer@$host"

    /** A direct chat message carrying one signal payload. */
    fun directMessage(to: String, id: String, dataXml: String): String =
        """<message type="chat" to="${escape(to)}" id="${escape(id)}">$dataXml</message>"""

    fun inviteData(kind: CallKind, room: String, callId: String?): String =
        """<data type="call-invite" kind="${kind.wire}" room="${escape(room)}" callId="${escape(callId.orEmpty())}"/>"""

    fun stateData(state: String, callId: String?, room: String?): String =
        """<data type="call-state" state="${escape(state)}" callId="${escape(callId.orEmpty())}" room="${escape(room.orEmpty())}"/>"""

    // ---- Parsing ------------------------------------------------------------

    /** Parsed `<data type="call-*">` payload. */
    sealed interface Signal {
        data class Token(
            val token: String,
            val room: String,
            val kind: String?,
            val callId: String?,
            /** `livekit` (also for servers that send no `mode`) or `p2p`. */
            val mode: String = MODE_LIVEKIT,
            /** Group room call. Always false for p2p, which is 1:1 by construction. */
            val group: Boolean = false,
            /** Public LiveKit URL for `mode=livekit`; null when absent or not a ws(s)/http(s) URL. */
            val livekitUrl: String? = null,
            /** Plain-text caller name; empty when absent or a raw chat name. */
            val callerName: String = "",
            /** Caller's xmppUsername when well formed. */
            val caller: String? = null,
            /** ICE servers of the callee for `mode=p2p` (JSON `ice` attribute); empty when absent. */
            val iceServers: List<IceServerConfig> = emptyList()
        ) : Signal {
            val isP2p: Boolean get() = mode == MODE_P2P
        }

        /** Peer to peer WebRTC signalling (`call-p2p`: offer, answer, candidate, end). */
        data class P2p(val signal: P2PSignal) : Signal

        data class Invite(
            val kind: CallKind,
            val room: String?,
            val callId: String?
        ) : Signal

        data class State(
            val state: String,
            val room: String?,
            val callId: String?,
            val callerXmppUsername: String?,
            val durationMs: Long?,
            val kind: String?,
            /** True for the server call log (it carries a caller and/or a duration). */
            val hasLogData: Boolean,
            /** Group call flag: one member declining must not end the call for everybody. */
            val group: Boolean = false
        ) : Signal

        /** Unknown `call-*` type: swallowed so it never reaches chat. */
        data class Other(val type: String) : Signal
    }

    /**
     * Returns the call signal carried by [innerMessageXml] (an already unwrapped
     * `<message>`), or null when it has no `<data type="call-*">` element.
     */
    fun parseSignal(innerMessageXml: String): Signal? {
        val attrs = attributesOf(innerMessageXml, DATA_TAG) ?: return null
        val type = attrs["type"]?.trim()?.lowercase().orEmpty()
        if (!type.startsWith("call-")) return null
        val callId = attrs["callId"]?.takeIf { it.isNotBlank() } ?: attrs["callid"]?.takeIf { it.isNotBlank() }
        return when (type) {
            "call-token" -> {
                // Old servers send neither `mode` nor `group`: that is a plain LiveKit 1:1 call.
                val mode = if (attrs["mode"].orEmpty().trim().equals(MODE_P2P, ignoreCase = true)) MODE_P2P else MODE_LIVEKIT
                Signal.Token(
                    token = attrs["token"].orEmpty().trim(),
                    room = attrs["room"].orEmpty().trim(),
                    kind = attrs["kind"],
                    callId = callId,
                    mode = mode,
                    group = mode == MODE_LIVEKIT && isTrueish(attrs["group"]),
                    livekitUrl = if (mode == MODE_LIVEKIT) normalizeLivekitUrl(attrs["livekitUrl"]) else null,
                    callerName = sanitizeCallName(attrs["callerName"]),
                    caller = sanitizeXmppUsername(attrs["caller"]),
                    iceServers = if (mode == MODE_P2P) normalizeIceServers(attrs["ice"]) else emptyList()
                )
            }
            P2PSignalStanza.TYPE -> P2PSignalStanza.parse(innerMessageXml)?.let { Signal.P2p(it) } ?: Signal.Other(type)
            "call-invite" -> Signal.Invite(
                kind = CallKind.fromWire(attrs["kind"]),
                room = attrs["room"]?.trim()?.takeIf { it.isNotEmpty() },
                callId = callId
            )
            // Server relay of a callee's decline: a terminal `declined` for 1:1, ignored in a group.
            "call-state", "call-state-declined" -> {
                val declinedRelay = type == "call-state-declined"
                val caller = attrs["callerXmppUsername"]?.trim()?.takeIf { it.isNotEmpty() }
                val durationRaw = attrs["durationMs"]?.takeIf { it.isNotEmpty() }
                Signal.State(
                    state = if (declinedRelay) "declined" else (attrs["state"] ?: attrs["event"]).orEmpty().trim().lowercase(),
                    room = attrs["room"]?.trim()?.takeIf { it.isNotEmpty() },
                    callId = callId,
                    callerXmppUsername = caller,
                    durationMs = durationRaw?.toDoubleOrNull()?.toLong(),
                    kind = attrs["kind"],
                    hasLogData = caller != null || durationRaw != null,
                    group = isTrueish(attrs["group"])
                )
            }
            else -> Signal.Other(type)
        }
    }

    /** True when the archived/received `<message>` carries any `<data type="call-*">`. */
    fun isCallSignal(innerMessageXml: String): Boolean = parseSignal(innerMessageXml) != null

    /** The `from` attribute of the outer `<message>` (the MUC room JID for MUC traffic). */
    fun fromAttribute(xml: String): String? = attributesOf(xml, MESSAGE_TAG)?.get("from")

    /**
     * Freshness check for a call-token: the server stamps `<stanza-id id="…">`
     * with a millisecond timestamp, and a token older than 5 s is stale.
     */
    fun isFreshCallToken(innerMessageXml: String, now: Long): Boolean {
        val tag = STANZA_ID_TAG.find(innerMessageXml)?.value ?: return false
        val id = attributesOf(tag, STANZA_ID_TAG)?.get("id")
        val createdAt = extractNumericTimestamp(id) ?: return false
        return kotlin.math.abs(now - createdAt) <= MAX_CALL_TOKEN_AGE_MS
    }

    /**
     * Port of web `extractNumericTimestamp`: a numeric id keeps its first 13
     * digits (milliseconds); otherwise the first run of 13+ digits is used.
     */
    internal fun extractNumericTimestamp(value: String?): Long? {
        val source = value.orEmpty().trim()
        if (source.isEmpty()) return null
        val direct = source.toDoubleOrNull()
        if (direct != null && direct.isFinite() && direct > 0) {
            return if (source.length > 13) source.take(13).toLongOrNull() else direct.toLong()
        }
        val match = Regex("\\d{13,}").find(source)?.value ?: return null
        return match.take(13).toLongOrNull()
    }

    // ---- Call-token attribute hygiene (web calls/callMode.ts, callDisplay.ts, senderTrust.ts) ----

    const val MODE_LIVEKIT = "livekit"
    const val MODE_P2P = "p2p"

    private val LIVEKIT_URL = Regex("^(wss?|https?)://\\S+$", RegexOption.IGNORE_CASE)
    private val SYSTEM_ACCOUNT_LOCAL = Regex("^([0-9a-fA-F]{24})_\\1$")
    private val XMPP_USERNAME = Regex("^[A-Za-z0-9_-]{1,128}(?:@[A-Za-z0-9.-]+)?$")
    private val UNWANTED_CHARS = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")
    private const val MAX_CALL_NAME_LENGTH = 80

    internal fun isTrueish(value: String?): Boolean =
        value?.trim()?.lowercase().let { it == "true" || it == "1" }

    /**
     * A LiveKit server URL as the backend publishes it. Only ws(s) and http(s)
     * URLs are accepted: the value becomes the connect target of the media
     * session, so anything else is treated as unset.
     */
    internal fun normalizeLivekitUrl(raw: String?): String? =
        raw?.trim()?.takeIf { LIVEKIT_URL.matches(it) }

    /** Display-safe caller name: control characters stripped, capped, raw ids refused ("" when unusable). */
    internal fun sanitizeCallName(raw: String?): String {
        if (raw == null) return ""
        val cleaned = raw.replace(UNWANTED_CHARS, " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        val capped = cleaned.codePoints().limit(MAX_CALL_NAME_LENGTH.toLong()).toArray()
            .let { String(it, 0, it.size) }.trim()
        return if (RAW_ID.matches(capped)) "" else capped
    }

    internal fun sanitizeXmppUsername(raw: String?): String? {
        val local = raw?.trim()?.substringBefore("/").orEmpty()
        return local.takeIf { XMPP_USERNAME.matches(it) }
    }

    /**
     * True when [fromJid] is the app's system account (`<appId>_<appId>`, the
     * resource is ignored). Only that account may steer where a call's media
     * goes, because the server stamps the real JID on every client message.
     */
    internal fun isSystemAccountSender(fromJid: String?, expectedDomain: String?): Boolean {
        val bare = fromJid?.trim()?.substringBefore("/").orEmpty()
        val at = bare.indexOf('@')
        if (at <= 0 || bare.indexOf('@', at + 1) != -1) return false
        val domain = bare.substring(at + 1).lowercase()
        if (domain.isEmpty() || !SYSTEM_ACCOUNT_LOCAL.matches(bare.substring(0, at))) return false
        val expected = expectedDomain?.trim()?.lowercase().orEmpty()
        return expected.isEmpty() || domain == expected
    }

    // ---- JWT (claims only, no signature check: the SFU validates the token) ----

    /** Decodes the JWT payload object, or null when [token] is not a parseable JWT. */
    fun jwtPayload(token: String): JsonObject? {
        if (token.isBlank()) return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val base64 = parts[1].replace('-', '+').replace('_', '/')
            val json = String(java.util.Base64.getDecoder().decode(base64), Charsets.UTF_8)
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Caller display name from the JWT `sub` claim. Raw ids (long hex-like
     * strings the server falls back to) are rejected so they never show as a name.
     */
    fun callerDisplayName(payload: JsonObject?): String {
        val sub = payload?.get("sub")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (sub.isEmpty() || RAW_ID.matches(sub)) return ""
        return sub
    }

    /** `callId` from the JWT `metadata` claim (a JSON string or an object). */
    fun callIdFromJwt(payload: JsonObject?): String? {
        val metadata: JsonElement = payload?.get("metadata") ?: return null
        val obj: JsonObject? = when {
            metadata.isJsonObject -> metadata.asJsonObject
            metadata.isJsonPrimitive && metadata.asJsonPrimitive.isString ->
                try {
                    JsonParser.parseString(metadata.asString).takeIf { it.isJsonObject }?.asJsonObject
                } catch (e: Exception) {
                    null
                }
            else -> null
        }
        val callId = obj?.get("callId")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        return callId.takeIf { it.isNotEmpty() }
    }

    // ---- Attribute helpers ---------------------------------------------------

    /** Attributes of the first start tag matched by [tag] inside [xml]. */
    internal fun attributesOf(xml: String, tag: Regex): Map<String, String>? {
        val startTag = tag.find(xml)?.value ?: return null
        val result = LinkedHashMap<String, String>()
        for (match in START_TAG_ATTR.findAll(startTag)) {
            val name = match.groupValues[1]
            val raw = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
            result.putIfAbsent(name, unescape(raw))
        }
        return result
    }

    private fun unescape(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
