package com.ethora.chat.core.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsFallbackTest {
    @Test
    fun `only explicit DNS overrides are used`() {
        val overrides = mapOf("xmpp.example.com" to "203.0.113.10")

        assertEquals("203.0.113.10", DnsFallback.resolveOverride("xmpp.example.com", overrides))
        assertNull(DnsFallback.resolveOverride("xmpp.chat.ethora.com", overrides))
        assertNull(DnsFallback.resolveOverride("api.chat.ethora.com", null))
    }
}
