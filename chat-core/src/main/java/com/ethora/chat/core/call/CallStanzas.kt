package com.ethora.chat.core.call

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
            val callId: String?
        ) : Signal

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
            val hasLogData: Boolean
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
            "call-token" -> Signal.Token(
                token = attrs["token"].orEmpty().trim(),
                room = attrs["room"].orEmpty().trim(),
                kind = attrs["kind"],
                callId = callId
            )
            "call-invite" -> Signal.Invite(
                kind = CallKind.fromWire(attrs["kind"]),
                room = attrs["room"]?.trim()?.takeIf { it.isNotEmpty() },
                callId = callId
            )
            "call-state" -> {
                val caller = attrs["callerXmppUsername"]?.trim()?.takeIf { it.isNotEmpty() }
                val durationRaw = attrs["durationMs"]?.takeIf { it.isNotEmpty() }
                Signal.State(
                    state = (attrs["state"] ?: attrs["event"]).orEmpty().trim().lowercase(),
                    room = attrs["room"]?.trim()?.takeIf { it.isNotEmpty() },
                    callId = callId,
                    callerXmppUsername = caller,
                    durationMs = durationRaw?.toDoubleOrNull()?.toLong(),
                    kind = attrs["kind"],
                    hasLogData = caller != null || durationRaw != null
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
