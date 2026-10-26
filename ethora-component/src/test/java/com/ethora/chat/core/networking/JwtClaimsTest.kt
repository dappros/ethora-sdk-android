package com.ethora.chat.core.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JwtClaimsTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.sig"
    }

    @Test
    fun `reads exp claim as epoch millis`() {
        assertEquals(1_900_000_000_000L, JwtClaims.expiryEpochMillis(jwt("""{"exp":1900000000,"sub":"u"}""")))
    }

    @Test
    fun `missing or malformed tokens have no expiry`() {
        assertNull(JwtClaims.expiryEpochMillis(jwt("""{"sub":"u"}""")))
        assertNull(JwtClaims.expiryEpochMillis("not-a-jwt"))
        assertNull(JwtClaims.expiryEpochMillis(null))
        assertNull(JwtClaims.expiryEpochMillis("a.%%%.c"))
    }

    @Test
    fun `token near expiry is refreshed, a fresh one is not`() {
        val now = 1_900_000_000_000L
        val nearExpiry = jwt("""{"exp":${(now + 60_000L) / 1000}}""")
        val fresh = jwt("""{"exp":${(now + 3_600_000L) / 1000}}""")

        assertTrue(TokenManager.shouldRefreshToken(nearExpiry, now))
        assertFalse(TokenManager.shouldRefreshToken(fresh, now))
        assertFalse(TokenManager.shouldRefreshToken("opaque-token", now))
    }
}
