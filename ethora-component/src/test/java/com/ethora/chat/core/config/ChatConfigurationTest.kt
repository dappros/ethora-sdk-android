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

    @Test
    fun `xmppServerUrl with non-websocket scheme fails with helpful message`() {
        // Field-bug shape: a misconfigured SDK init passes an http://
        // (or no-scheme) URL where xmppServerUrl is expected. Without
        // the explicit scheme check the connection would fail much
        // later with an opaque "connection refused" — the validation
        // surfaces a clear error at config time instead, which is
        // what the no-fallback policy promises (Cluster H in the
        // cross-platform QA scenario catalog).
        ChatStore.setConfig(
            ChatConfig(
                baseUrl = "https://api.example.com/v1",
                appId = "app-1",
                xmppSettings = XMPPSettings(
                    xmppServerUrl = "http://xmpp.example.com/ws", // wrong scheme
                    host = "xmpp.example.com",
                    conference = "conference.xmpp.example.com"
                )
            )
        )

        val error = assertIllegalState { ChatStore.getEffectiveXmppSettings() }

        assertTrue(
            "error must call out the scheme requirement explicitly",
            error.message?.contains("ws://") == true || error.message?.contains("wss://") == true
        )
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
