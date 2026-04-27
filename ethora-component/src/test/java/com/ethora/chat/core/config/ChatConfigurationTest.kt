package com.ethora.chat.core.config

import com.ethora.chat.core.store.ChatStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatConfigurationTest {
    @Test
    fun `missing base URL fails explicitly instead of using Ethora fallback`() {
        ChatStore.setConfig(ChatConfig())

        val error = assertIllegalState { ChatStore.getEffectiveBaseUrl() }

        assertTrue(error.message?.contains("baseUrl") == true)
    }

    @Test
    fun `missing XMPP settings fail explicitly instead of using Ethora fallback`() {
        ChatStore.setConfig(ChatConfig(baseUrl = "https://api.example.com/v1", appId = "app-1"))

        val error = assertIllegalState { ChatStore.getEffectiveXmppSettings() }

        assertTrue(error.message?.contains("xmppSettings") == true)
    }

    @Test
    fun `configured server values are returned unchanged`() {
        val settings = XMPPSettings(
            xmppServerUrl = "wss://xmpp.example.com/ws",
            host = "xmpp.example.com",
            conference = "conference.xmpp.example.com"
        )
        ChatStore.setConfig(
            ChatConfig(
                baseUrl = "https://api.example.com/v1",
                appId = "app-1",
                xmppSettings = settings
            )
        )

        assertEquals("https://api.example.com/v1", ChatStore.getEffectiveBaseUrl())
        assertEquals("app-1", ChatStore.getEffectiveAppId())
        assertEquals("conference.xmpp.example.com", ChatStore.getEffectiveConference())
        assertEquals(settings, ChatStore.getEffectiveXmppSettings())
    }

    private fun assertIllegalState(block: () -> Unit): IllegalStateException {
        return try {
            block()
            fail("Expected IllegalStateException")
            throw AssertionError("unreachable")
        } catch (error: IllegalStateException) {
            error
        }
    }
}
