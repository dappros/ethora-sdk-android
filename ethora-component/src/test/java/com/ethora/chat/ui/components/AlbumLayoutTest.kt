package com.ethora.chat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumLayoutTest {

    @Test
    fun `layout follows the web album shapes`() {
        assertEquals(AlbumLayout(AlbumKind.FULL, 1, 0), AlbumLayouts.forCount(1))
        assertEquals(AlbumLayout(AlbumKind.PAIR, 2, 0), AlbumLayouts.forCount(2))
        assertEquals(AlbumLayout(AlbumKind.HERO, 3, 0), AlbumLayouts.forCount(3))
        assertEquals(AlbumLayout(AlbumKind.QUAD, 4, 0), AlbumLayouts.forCount(4))
    }

    @Test
    fun `five or more items show four tiles and count the rest`() {
        assertEquals(AlbumLayout(AlbumKind.QUAD, 4, 1), AlbumLayouts.forCount(5))
        assertEquals(AlbumLayout(AlbumKind.QUAD, 4, 6), AlbumLayouts.forCount(10))
    }
}
