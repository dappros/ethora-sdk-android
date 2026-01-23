package com.ethora.chat.core.config

/**
 * Typing indicator position
 */
enum class TypingIndicatorPosition {
    BOTTOM,
    TOP,
    OVERLAY,
    FLOATING
}

/**
 * Custom typing indicator configuration
 */
data class CustomTypingIndicatorConfig(
    val enabled: Boolean,
    val text: String? = null,
    val textFunction: ((List<String>) -> String)? = null,
    val position: TypingIndicatorPosition? = null,
    val styles: Map<String, Any>? = null
) {
    fun getText(usersTyping: List<String>): String {
        return textFunction?.invoke(usersTyping)
            ?: text
            ?: "${usersTyping.joinToString(", ")} ${if (usersTyping.size == 1) "is" else "are"} typing..."
    }
}
