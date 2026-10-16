package com.ethora.chat.core.networking

import com.google.gson.JsonParser
import java.util.Base64

/** Reads the `exp` claim of a JWT without verifying its signature. */
object JwtClaims {
    /** Expiry as epoch milliseconds, or null when the value is not a JWT with an `exp` claim. */
    fun expiryEpochMillis(jwt: String?): Long? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            val exp = JsonParser.parseString(json).asJsonObject.get("exp") ?: return null
            if (!exp.isJsonPrimitive) return null
            exp.asLong * 1000L
        } catch (_: Exception) {
            null
        }
    }

    /** Milliseconds until expiry (negative once expired), or null when unknown. */
    fun millisUntilExpiry(jwt: String?, nowMillis: Long = System.currentTimeMillis()): Long? =
        expiryEpochMillis(jwt)?.let { it - nowMillis }
}
