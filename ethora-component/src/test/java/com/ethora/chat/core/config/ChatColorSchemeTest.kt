package com.ethora.chat.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatColorSchemeTest {
    @Test
    fun `explicit colorScheme wins over legacy forceDarkTheme`() {
        assertEquals(false, resolveDarkTheme(ChatColorScheme.LIGHT, forceDarkTheme = true))
        assertEquals(true, resolveDarkTheme(ChatColorScheme.DARK, forceDarkTheme = false))
    }

    @Test
    fun `system colorScheme defers to the OS even when forceDarkTheme is set`() {
        assertNull(resolveDarkTheme(ChatColorScheme.SYSTEM, forceDarkTheme = true))
    }

    @Test
    fun `without colorScheme the legacy forceDarkTheme is used, and unset follows the system`() {
        assertEquals(true, resolveDarkTheme(null, forceDarkTheme = true))
        assertEquals(false, resolveDarkTheme(null, forceDarkTheme = false))
        assertNull(resolveDarkTheme(null, forceDarkTheme = null))
    }

    @Test
    fun `builder carries colorScheme and twoPaneLayout into ChatConfig`() {
        val config = ChatConfig.builder()
            .colorScheme(ChatColorScheme.DARK)
            .twoPaneLayout(false)
            .build()

        assertEquals(ChatColorScheme.DARK, config.colorScheme)
        assertEquals(false, config.twoPaneLayout)
    }

    @Test
    fun `font scale is fontSize over the 16sp base and 1 when unset or invalid`() {
        assertEquals(1f, typographyFontScale(null), 0f)
        assertEquals(1f, typographyFontScale(16f), 0f)
        assertEquals(1.125f, typographyFontScale(18f), 0.0001f)
        assertEquals(1f, typographyFontScale(0f), 0f)
        assertEquals(1f, typographyFontScale(-4f), 0f)
        assertEquals(1f, typographyFontScale(Float.NaN), 0f)
    }
}
