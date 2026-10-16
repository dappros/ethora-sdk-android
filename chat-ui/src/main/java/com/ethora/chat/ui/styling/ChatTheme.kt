package com.ethora.chat.ui.styling

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.ethora.chat.core.config.BackgroundChatConfig
import com.ethora.chat.core.config.MessageBubbleStyle
import com.ethora.chat.core.config.ChatColors as ConfigColors
import com.ethora.chat.core.config.ChatTypographyConfig
import com.ethora.chat.core.config.typographyFontScale

/**
 * Light color scheme
 */
fun lightChatColorScheme(
    colors: ConfigColors? = null
): ColorScheme = lightColorScheme(
    primary = parseHexColor(colors?.primary) ?: ChatColors.Primary,
    secondary = parseHexColor(colors?.secondary) ?: ChatColors.Secondary,
    background = ChatColors.Background,
    surface = ChatColors.Surface,
    onPrimary = ChatColors.OnPrimary,
    onSecondary = ChatColors.OnSecondary,
    onBackground = ChatColors.OnBackground,
    onSurface = ChatColors.OnSurface,
    error = ChatColors.Error
)

/**
 * Dark color scheme, built from the web SDK's dark tokens (see [ChatDarkPalette]).
 * Dark-variant config colours (`primaryDark`, `secondaryDark`) win over the
 * regular `primary`/`secondary` when present. Without `primaryDark`, the
 * primary is lifted slightly (as web does) so primary-coloured text and icons
 * stay legible on the dark surface, while buttons keep white text on it.
 */
fun darkChatColorScheme(
    colors: ConfigColors? = null
): ColorScheme {
    val configuredPrimary = parseHexColor(colors?.primary) ?: ChatColors.Primary
    val primary = parseHexColor(colors?.primaryDark) ?: tintTowardWhite(configuredPrimary, 0.2f)
    return darkColorScheme(
        primary = primary,
        onPrimary = ChatColors.OnPrimary,
        primaryContainer = mixColors(configuredPrimary, ChatDarkPalette.Bg, 0.72f),
        onPrimaryContainer = ChatDarkPalette.Text,
        secondary = parseHexColor(colors?.secondaryDark ?: colors?.secondary) ?: ChatColors.Secondary,
        onSecondary = ChatColors.OnSecondary,
        background = ChatDarkPalette.ChatBg,
        onBackground = ChatDarkPalette.Text,
        surface = ChatDarkPalette.Bg,
        onSurface = ChatDarkPalette.Text,
        surfaceVariant = ChatDarkPalette.BgHover,
        // Incoming message bodies read onSurfaceVariant, so keep it at full text strength.
        onSurfaceVariant = ChatDarkPalette.Text,
        surfaceContainerLow = ChatDarkPalette.BgSubtle,
        surfaceContainer = ChatDarkPalette.BgSubtle,
        surfaceContainerHigh = ChatDarkPalette.BgHover,
        surfaceContainerHighest = ChatDarkPalette.BgHover,
        outline = ChatDarkPalette.TextMuted,
        outlineVariant = ChatDarkPalette.Border,
        error = ChatDarkPalette.Danger,
        onError = ChatDarkPalette.Bg
    )
}

/**
 * Chat theme composable. Wraps content in MaterialTheme and additionally
 * publishes a [LocalChatThemeOverrides] so individual components (bubbles,
 * chat background) can pick up host-provided per-mode hex overrides without
 * each one having to read [com.ethora.chat.core.config.ChatConfig] directly.
 *
 * @param darkTheme  Whether to render in dark mode. Defaults to system pref.
 *                   Hosts can wire `config.forceDarkTheme` here.
 * @param colors     Brand `primary` / `secondary` (and optional `*Dark`).
 * @param bubble     Per-bubble override (sent/received background + text,
 *                   with optional `*Dark` siblings).
 * @param background Chat-screen background colour/image (with `*Dark`).
 */
@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: ConfigColors? = null,
    bubble: MessageBubbleStyle? = null,
    background: BackgroundChatConfig? = null,
    typography: ChatTypographyConfig? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkChatColorScheme(colors) else lightChatColorScheme(colors)
    val overrides = resolveOverrides(bubble, background, colors, darkTheme)

    // Resolve the host-provided font (Google Font by name or bundled res/font);
    // null keeps the platform default, identical to prior behaviour.
    val fontFamily = rememberChatFontFamily(typography)
    val fontScale = typographyFontScale(typography?.fontSize)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = chatTypography(fontFamily, fontScale)
    ) {
        // Also publish the family on LocalTextStyle so that bare `Text("…")`
        // calls — those that don't reference a MaterialTheme.typography token —
        // inherit the configured font too. This is the Compose equivalent of
        // the web SDK's `--ethora-font-family` cascade. When no font is
        // configured, leave LocalTextStyle untouched (default behaviour).
        val baseTextStyle = LocalTextStyle.current
        val resolvedTextStyle = if (fontFamily != null) {
            baseTextStyle.copy(fontFamily = fontFamily)
        } else {
            baseTextStyle
        }
        CompositionLocalProvider(
            LocalChatThemeOverrides provides overrides,
            LocalTextStyle provides resolvedTextStyle,
            // Bare Text() calls with no explicit colour would otherwise fall back
            // to Material's default black, which is invisible on the dark surface.
            LocalContentColor provides colorScheme.onSurface
        ) {
            content()
        }
    }
}
