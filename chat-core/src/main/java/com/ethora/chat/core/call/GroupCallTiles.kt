package com.ethora.chat.core.call

/** One participant as the group call grid sees it. */
data class GroupTileInput(
    val id: String,
    val name: String,
    val isLocal: Boolean,
    val speaking: Boolean,
    /** A live, unmuted camera track exists. */
    val hasVideo: Boolean,
    val micMuted: Boolean
)

data class GroupTileSelection(
    /** The tiles to draw, in display order. */
    val tiles: List<GroupTileInput>,
    /** Participants that did not fit ("+N more"). */
    val hidden: Int
)

/**
 * Which participants get a tile in a group call and how the grid is laid out (web
 * `GroupCallContent`: us first, the others in join order, a highlighted active speaker).
 * A phone screen fits at most [MAX_TILES]; when there are more, the people talking and the
 * people with video are kept visible and the rest collapse into a "+N more" counter.
 */
object GroupCallTiles {
    const val MAX_TILES = 9

    fun select(all: List<GroupTileInput>, max: Int = MAX_TILES): GroupTileSelection {
        val local = all.filter { it.isLocal }
        val others = all.filter { !it.isLocal }
        val ordered = local + others
        if (ordered.size <= max) return GroupTileSelection(ordered, 0)

        val slots = (max - local.size).coerceAtLeast(0)
        // Speakers first, then people showing video, then join order. Display order stays the
        // join order so tiles do not shuffle every time somebody speaks.
        val ranked = others.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<GroupTileInput>> { it.value.speaking }
                    .thenByDescending { it.value.hasVideo }
                    .thenBy { it.index }
            )
            .take(slots)
            .map { it.index }
            .toSet()
        val shownOthers = others.filterIndexed { index, _ -> index in ranked }
        val shown = local + shownOthers
        return GroupTileSelection(shown, ordered.size - shown.size)
    }

    /** Grid columns for [count] tiles. Two people stack in portrait and sit side by side in landscape. */
    fun columns(count: Int, landscape: Boolean = false): Int = when {
        count <= 1 -> 1
        count == 2 -> if (landscape) 2 else 1
        count <= 6 -> 2
        else -> 3
    }.let { if (landscape && count in 3..6) minOf(3, count) else it }

    fun rows(count: Int, columns: Int): Int = if (count <= 0) 0 else (count + columns - 1) / columns
}
