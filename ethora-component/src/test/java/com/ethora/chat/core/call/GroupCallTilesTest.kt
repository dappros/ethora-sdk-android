package com.ethora.chat.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCallTilesTest {
    private fun tile(
        id: String,
        local: Boolean = false,
        speaking: Boolean = false,
        video: Boolean = false,
        muted: Boolean = false
    ) = GroupTileInput(id, id, local, speaking, video, muted)

    @Test
    fun `we are first, the others keep their join order`() {
        val selection = GroupCallTiles.select(listOf(tile("a"), tile("me", local = true), tile("b")))
        assertEquals(listOf("me", "a", "b"), selection.tiles.map { it.id })
        assertEquals(0, selection.hidden)
    }

    @Test
    fun `nine participants all fit`() {
        val all = listOf(tile("me", local = true)) + (1..8).map { tile("p$it") }
        val selection = GroupCallTiles.select(all)
        assertEquals(9, selection.tiles.size)
        assertEquals(0, selection.hidden)
    }

    @Test
    fun `beyond nine, speakers then video stay and the rest collapse`() {
        val others = (1..12).map { i ->
            tile("p$i", speaking = i == 11, video = i == 10 || i == 12)
        }
        val selection = GroupCallTiles.select(listOf(tile("me", local = true)) + others)

        assertEquals(9, selection.tiles.size)
        assertEquals(4, selection.hidden)
        assertEquals("us first", "me", selection.tiles.first().id)
        val ids = selection.tiles.map { it.id }
        assertTrue("the speaker is kept", "p11" in ids)
        assertTrue("people with video are kept next", "p10" in ids && "p12" in ids)
        assertEquals("display order is the join order, not the rank", ids.drop(1), ids.drop(1).sortedBy { it.removePrefix("p").toInt() })
    }

    @Test
    fun `we always get a tile even when everybody else speaks`() {
        val others = (1..20).map { tile("p$it", speaking = true) }
        val selection = GroupCallTiles.select(listOf(tile("me", local = true)) + others, max = 4)
        assertEquals(4, selection.tiles.size)
        assertEquals("me", selection.tiles.first().id)
        assertEquals(17, selection.hidden)
    }

    @Test
    fun `grid columns follow the tile count`() {
        assertEquals(1, GroupCallTiles.columns(1))
        assertEquals(1, GroupCallTiles.columns(2))
        assertEquals(2, GroupCallTiles.columns(2, landscape = true))
        assertEquals(2, GroupCallTiles.columns(4))
        assertEquals(2, GroupCallTiles.columns(6))
        assertEquals(3, GroupCallTiles.columns(7))
        assertEquals(3, GroupCallTiles.columns(9))
        assertEquals(3, GroupCallTiles.rows(9, 3))
        assertEquals(3, GroupCallTiles.rows(5, 2))
        assertEquals(0, GroupCallTiles.rows(0, 2))
    }
}
