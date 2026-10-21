package com.ethora.chat.ui.styling

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Dark-scheme neutrals, copied from the web SDK's `DARK_DEFAULTS` in
 * `src/styles/tokens.ts`, so Android and web render the same dark surfaces.
 * Text values clear WCAG AA (4.5:1) on [Bg], [BgSubtle] and [BgHover].
 */
internal object ChatDarkPalette {
    val Text = Color(0xFFECEEF1)
    val TextSecondary = Color(0xFFB4B9C1)
    val TextMuted = Color(0xFF9AA1AB)
    /** Header, input bar, list surfaces (web `bg`). */
    val Bg = Color(0xFF1A1C21)
    val BgSubtle = Color(0xFF202329)
    /** Hovered rows and incoming bubbles (web `bgHover`). */
    val BgHover = Color(0xFF2A2D34)
    val Border = Color(0xFF34373F)
    val Danger = Color(0xFFF2766B)
    /** Message area background (web `chatBg`). */
    val ChatBg = Color(0xFF141619)
    /** Seam between two surfaces (web `divider`). */
    val Divider = Color(0xFF0F1114)
}

/** Mix [color] toward white by [amount] (0-1). Same as web `tintColor`. */
internal fun tintTowardWhite(color: Color, amount: Float): Color = mixColors(color, Color.White, amount)

/** Mix [from] toward [to] by [amount] (0-1). Same as web `mixColor`. */
internal fun mixColors(from: Color, to: Color, amount: Float): Color =
    // Per-channel in sRGB, like web: Compose's own lerp interpolates in Oklab and gives other colours.
    Color(
        red = from.red + (to.red - from.red) * amount,
        green = from.green + (to.green - from.green) * amount,
        blue = from.blue + (to.blue - from.blue) * amount,
        alpha = from.alpha + (to.alpha - from.alpha) * amount
    )

/** True when the scheme's surface is dark. Used where a literal colour would otherwise be light-only. */
internal fun isDarkSurface(surface: Color): Boolean = surface.luminance() < 0.5f
