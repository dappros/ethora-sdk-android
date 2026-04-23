package com.ethora.chat.core.xmpp

import java.util.Date

/**
 * One-for-one port of `web/src/helpers/timestamp.ts`.
 *
 * Ethora's server-assigned archive/stanza IDs (and sometimes the message id
 * attribute itself) are NUMERIC timestamps — either in ms, µs or ns. Web relies
 * on this fact to derive a stable message timestamp even when the server omits
 * `<delay stamp="…"/>` on MAM forwards. We do the exact same thing so history
 * ordering on Android matches the web client bit-for-bit.
 */
object TimestampUtils {
    private const val MILLISECONDS_LOWER_BOUND = 1e11
    private const val MICROSECONDS_UPPER_BOUND = 1e14
    private const val NANOS_UPPER_BOUND = 1e17

    fun normalizeTimestampValue(value: Double): Long {
        if (!value.isFinite() || value <= 0) return 0L
        return when {
            value < MILLISECONDS_LOWER_BOUND -> (value * 1000.0).toLong()           // s  → ms
            value > NANOS_UPPER_BOUND -> (value / 1_000_000.0).toLong()             // ns → ms
            value > MICROSECONDS_UPPER_BOUND -> (value / 1000.0).toLong()           // µs → ms
            else -> value.toLong()                                                   // already ms
        }
    }

    /**
     * Accepts anything web's helper accepts: Date, Number, String. Returns 0
     * when the input can't be resolved to a positive millisecond timestamp.
     *
     * Strings are tried in three ways:
     *   1. all-digits  → parse as a number and normalise
     *   2. ISO / date-like → Date.parse
     *   3. contains a numeric chunk of 10+ digits (e.g. "send-text-message-1704067200000")
     */
    fun getTimestampFromUnknown(value: Any?): Long {
        if (value == null) return 0L

        if (value is Date) {
            val ts = value.time
            return if (ts > 0) ts else 0L
        }

        if (value is Number) {
            return normalizeTimestampValue(value.toDouble())
        }

        if (value is String) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return 0L

            if (trimmed.matches(Regex("^\\d+$"))) {
                val n = trimmed.toDoubleOrNull() ?: return 0L
                return normalizeTimestampValue(n)
            }

            // Date-string parse (ISO-8601 etc.)
            val iso = tryParseDateString(trimmed)
            if (iso > 0) return iso

            // Pick the first 10+ digit run from the string
            val chunk = Regex("\\d{10,}").find(trimmed)?.value
            if (chunk != null) {
                val n = chunk.toDoubleOrNull() ?: return 0L
                return normalizeTimestampValue(n)
            }
        }

        return 0L
    }

    private fun tryParseDateString(s: String): Long {
        // Fast path: XEP-0082 / ISO-8601
        try {
            return java.time.Instant.parse(s).toEpochMilli()
        } catch (_: Exception) { /* fall through */ }

        // Legacy XEP-0091 compact: 20241003T15:30:10
        try {
            val legacy = java.text.SimpleDateFormat("yyyyMMdd'T'HH:mm:ss").apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val d = legacy.parse(s)
            if (d != null && d.time > 0) return d.time
        } catch (_: Exception) { /* fall through */ }

        return 0L
    }
}
