package com.ethora.chat.ui.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ethora.chat.core.config.BackgroundChatConfig
import com.ethora.chat.core.config.MessageBubbleStyle
import com.ethora.chat.core.config.ChatColors as ConfigColors

/**
 * Resolved colour overrides for the SDK's chat surface. Populated by
 * [ChatTheme] from the host's [com.ethora.chat.core.config.ChatConfig]
 * (`bubleMessage` + `backgroundChat`) for the current light/dark mode,
 * and read by individual composables — see e.g. [MessageBubble] and
 * [ChatRoomView] — that prefer a host override over the MaterialTheme
 * default.
 *
 * Any null field means "fall through to MaterialTheme" — callers should
 * never use these fields blindly; resolve them with `?: MaterialTheme...`
 * at the call site so a partial override stays partial.
 */
data class ChatThemeOverrides(
    val outgoingBubbleBackground: Color? = null,
    val incomingBubbleBackground: Color? = null,
    val outgoingBubbleText: Color? = null,
    val incomingBubbleText: Color? = null,
    val chatBackground: Color? = null,
    val chatBackgroundImage: String? = null,
    val headerBackground: Color? = null,
    val inputBarBackground: Color? = null,
    val inputTextColor: Color? = null,
    /** Tint for the chat's icons (web `colors.icons`). Null = primary. */
    val iconTint: Color? = null
) {
    companion object {
        val Empty = ChatThemeOverrides()
    }
}

val LocalChatThemeOverrides = compositionLocalOf { ChatThemeOverrides.Empty }

/**
 * Build a [ChatThemeOverrides] for the given mode by resolving the host's
 * config hex strings into Compose [Color]s, preferring the `*Dark` variant
 * when `darkTheme` is true and a dark override is present.
 */
internal fun resolveOverrides(
    bubble: MessageBubbleStyle?,
    background: BackgroundChatConfig?,
    colors: ConfigColors?,
    darkTheme: Boolean
): ChatThemeOverrides {
    if (bubble == null && background == null && colors == null) return ChatThemeOverrides.Empty
    fun pick(light: String?, dark: String?): Color? {
        val raw = if (darkTheme) (dark ?: light) else light
        return parseHexColor(raw)
    }
    return ChatThemeOverrides(
        // Web-style flat colour keys (`colors.ownMessageBackground` etc.) are the
        // fallback when the bubble / input style doesn't set the same thing.
        outgoingBubbleBackground = pick(bubble?.backgroundMessageUser, bubble?.backgroundMessageUserDark)
            ?: parseHexColor(colors?.ownMessageBackground),
        incomingBubbleBackground = pick(bubble?.backgroundMessage, bubble?.backgroundMessageDark)
            ?: parseHexColor(colors?.otherMessageBackground),
        outgoingBubbleText = pick(bubble?.colorUser, bubble?.colorUserDark),
        incomingBubbleText = pick(bubble?.color, bubble?.colorDark),
        chatBackground = pick(background?.color, background?.colorDark),
        chatBackgroundImage = if (darkTheme) {
            background?.imageDark ?: background?.image
        } else background?.image,
        headerBackground = pick(colors?.headerColor, colors?.headerColorDark),
        inputBarBackground = pick(colors?.inputBarColor, colors?.inputBarColorDark)
            ?: parseHexColor(colors?.inputBackground),
        inputTextColor = pick(colors?.inputTextColor, colors?.inputTextColorDark)
            ?: parseHexColor(colors?.colorInput),
        iconTint = parseHexColor(colors?.icons)
    )
}

internal fun parseHexColor(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    // Pure Kotlin (android.graphics.Color is not available to JVM tests): #RGB, #RRGGBB or #AARRGGBB.
    val hex = raw.trim().removePrefix("#")
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("").toLong(16).let { Color(0xFF000000L or it) }
        6 -> Color(0xFF000000L or hex.toLong(16))
        8 -> Color(hex.toLong(16))
        else -> null
    }
}

/** Convenience: read the current overrides at a callsite. */
@Composable
@ReadOnlyComposable
fun chatThemeOverrides(): ChatThemeOverrides = LocalChatThemeOverrides.current
