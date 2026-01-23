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
 */
data class NotificationOffset(
    val top: Float? = null,
    val bottom: Float? = null,
    val left: Float? = null,
    val right: Float? = null
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
