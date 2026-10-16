package com.ethora.chat.ui.styling

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Screen width (dp) from which the room list and the chat sit side by side. Material "medium" window class. */
const val TWO_PANE_MIN_WIDTH_DP: Int = 600

/** Fixed width of the room list in two-pane mode. */
val TWO_PANE_LIST_WIDTH: Dp = 360.dp

/**
 * Whether the room list and the chat render side by side.
 *
 * [twoPaneLayout] null (default) splits at [TWO_PANE_MIN_WIDTH_DP] and up,
 * `false` always renders a single pane, `true` always splits.
 */
fun shouldUseTwoPane(screenWidthDp: Int, twoPaneLayout: Boolean?): Boolean = when (twoPaneLayout) {
    true -> true
    false -> false
    null -> screenWidthDp >= TWO_PANE_MIN_WIDTH_DP
}
