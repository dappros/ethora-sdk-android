package com.ethora.chat.ui.styling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLayoutTest {
    @Test
    fun `auto layout splits at the 600dp breakpoint, not below`() {
        assertFalse(shouldUseTwoPane(599, twoPaneLayout = null))
        assertTrue(shouldUseTwoPane(TWO_PANE_MIN_WIDTH_DP, twoPaneLayout = null))
        assertTrue(shouldUseTwoPane(1024, twoPaneLayout = null))
    }

    @Test
    fun `explicit flag overrides the width`() {
        assertFalse(shouldUseTwoPane(1280, twoPaneLayout = false))
        assertTrue(shouldUseTwoPane(360, twoPaneLayout = true))
    }

    @Test
    fun `breakpoint constant matches the documented 600dp`() {
        assertEquals(600, TWO_PANE_MIN_WIDTH_DP)
    }
}
