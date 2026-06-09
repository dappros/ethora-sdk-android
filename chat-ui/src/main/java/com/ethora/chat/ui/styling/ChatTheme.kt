package com.ethora.chat.ui.styling

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
 * Dark color scheme. Dark-variant config colours (`primaryDark`, `secondaryDark`)
 * win over the regular `primary`/`secondary` when present, so a host can keep
 * the brand purple in light mode but switch to a desaturated tint in dark.
 */
fun darkChatColorScheme(
    colors: ConfigColors? = null
): ColorScheme = darkColorScheme(
    primary = parseHexColor(colors?.primaryDark ?: colors?.primary) ?: ChatColors.Primary,
    secondary = parseHexColor(colors?.secondaryDark ?: colors?.secondary) ?: ChatColors.Secondary,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = ChatColors.OnPrimary,
    onSecondary = ChatColors.OnSecondary,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    error = ChatColors.Error
)

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = chatTypography(fontFamily)
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
            LocalTextStyle provides resolvedTextStyle
        ) {
            content()
        }
    }
}
