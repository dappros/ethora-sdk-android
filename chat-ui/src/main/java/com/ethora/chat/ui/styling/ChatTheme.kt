package com.ethora.chat.ui.styling

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ethora.chat.core.config.ChatColors as ConfigColors

/**
 * Light color scheme
 */
fun lightChatColorScheme(
    colors: ConfigColors? = null
): ColorScheme = lightColorScheme(
    primary = colors?.primary?.let { Color(android.graphics.Color.parseColor(it)) } ?: ChatColors.Primary,
    secondary = colors?.secondary?.let { Color(android.graphics.Color.parseColor(it)) } ?: ChatColors.Secondary,
    background = ChatColors.Background,
    surface = ChatColors.Surface,
    onPrimary = ChatColors.OnPrimary,
    onSecondary = ChatColors.OnSecondary,
    onBackground = ChatColors.OnBackground,
    onSurface = ChatColors.OnSurface,
    error = ChatColors.Error
)

/**
 * Dark color scheme
 */
fun darkChatColorScheme(
    colors: ConfigColors? = null
): ColorScheme = darkColorScheme(
    primary = colors?.primary?.let { Color(android.graphics.Color.parseColor(it)) } ?: ChatColors.Primary,
    secondary = colors?.secondary?.let { Color(android.graphics.Color.parseColor(it)) } ?: ChatColors.Secondary,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = ChatColors.OnPrimary,
    onSecondary = ChatColors.OnSecondary,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    error = ChatColors.Error
)

/**
 * Chat theme composable
 */
@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: ConfigColors? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkChatColorScheme(colors)
    } else {
        lightChatColorScheme(colors)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ChatTypography,
        content = content
    )
}
