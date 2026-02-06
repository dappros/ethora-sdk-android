package com.ethora.chat.core.config

import com.ethora.chat.core.models.Message

/**
 * Notification horizontal position
 */
enum class NotificationHorizontalPosition {
    LEFT,
    RIGHT,
    CENTER
}

/**
 * Notification vertical position
 */
enum class NotificationVerticalPosition {
    TOP,
    BOTTOM
}

/**
 * Notification offset
 * Matches web: offset values can be number | string
 */
data class NotificationOffset(
    val top: Any? = null, // Float (number) or String (e.g., "20px", "1rem")
    val bottom: Any? = null,
    val left: Any? = null,
    val right: Any? = null
)

/**
 * Notification position
 */
data class NotificationPosition(
    val horizontal: NotificationHorizontalPosition? = null,
    val vertical: NotificationVerticalPosition? = null,
    val offset: NotificationOffset? = null
)

/**
 * Message notification parameters
 */
data class MessageNotificationParams(
    val roomJID: String,
    val messageId: String,
    val message: Message,
    val roomName: String,
    val senderName: String
)

/**
 * Message notification configuration
 */
data class MessageNotificationConfig(
    val enabled: Boolean? = null,
    val showInContext: Boolean? = null,
    val position: NotificationPosition? = null,
    val maxNotifications: Int? = null,
    val duration: Long? = null, // in milliseconds
    val onClick: ((MessageNotificationParams) -> Unit)? = null
)
