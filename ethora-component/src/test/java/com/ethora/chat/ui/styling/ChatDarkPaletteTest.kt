package com.ethora.chat.ui.styling

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDarkPaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertRgb(expected: Long, actual: Color) {
        val e = Color(expected)
        assertEquals(e.red, actual.red, 0.01f)
        assertEquals(e.green, actual.green, 0.01f)
        assertEquals(e.blue, actual.blue, 0.01f)
    }

    @Test
    fun `text tokens clear WCAG AA on every dark surface they render on`() {
        val surfaces = listOf(ChatDarkPalette.ChatBg, ChatDarkPalette.Bg, ChatDarkPalette.BgSubtle, ChatDarkPalette.BgHover)
        val texts = listOf(ChatDarkPalette.Text, ChatDarkPalette.TextSecondary, ChatDarkPalette.TextMuted)
        for (surface in surfaces) {
            for (text in texts) {
                assertTrue("contrast below 4.5 for $text on $surface", contrast(text, surface) >= 4.5)
            }
        }
    }

    @Test
    fun `dark scheme is dark and exposes readable on-surface colours`() {
        val scheme = darkChatColorScheme(null)

        assertTrue(isDarkSurface(scheme.surface))
        assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5)
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5)
        assertTrue(contrast(scheme.onBackground, scheme.background) >= 4.5)
    }

    @Test
    fun `light scheme is not treated as dark`() {
        assertFalse(isDarkSurface(lightChatColorScheme(null).surface))
    }

    @Test
    fun `default primary is lifted in dark the way web lifts it`() {
        // web: tintColor('#0052CD', 0.2) = #3375D7
        assertRgb(0xFF3375D7, darkChatColorScheme(null).primary)
    }

    @Test
    fun `explicit primaryDark is used as given`() {
        val colors = com.ethora.chat.core.config.ChatColors(
            primary = "#0052CD",
            secondary = "#F3F6FC",
            primaryDark = "#22AAFF"
        )
        assertRgb(0xFF22AAFF, darkChatColorScheme(colors).primary)
    }

    @Test
    fun `tint and mix follow the web helpers`() {
        assertRgb(0xFF808080, tintTowardWhite(Color(0xFF000000), 0.5f))
        assertRgb(0xFF000000, mixColors(Color(0xFFFFFFFF), Color(0xFF000000), 1f))
    }
}
