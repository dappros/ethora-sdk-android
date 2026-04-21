package com.ethora.chat.core.models

import androidx.compose.runtime.Composable

/**
 * Day separator props
 */
data class DaySeparatorProps(
    val date: java.util.Date,
    val formattedDate: String
)

/**
 * New message label props
 */
data class NewMessageLabelProps(
    val color: String? = null
)

/**
 * Decorated message
 */
data class DecoratedMessage(
    val message: Message,
    val showDateLabel: Boolean
)

/**
 * Scroll controller API
 */
interface ScrollControllerAPI {
    fun scrollToBottom()
    suspend fun waitForImagesLoaded()
    val showScrollButton: Boolean
    val newMessagesCount: Int
    fun resetNewMessageCounter()
}

/**
 * Custom scrollable area props
 */
data class CustomScrollableAreaProps(
    val roomJID: String,
    val messages: List<Message>,
    val decoratedMessages: List<DecoratedMessage>,
    val isLoading: Boolean,
    val isReply: Boolean,
    val activeMessage: Message?,
    val loadMoreMessages: suspend (String, Int, Long?) -> Unit,
    val renderMessage: @Composable (DecoratedMessage) -> Unit,
    val scrollController: ScrollControllerAPI,
    val typingIndicator: @Composable (() -> Unit)?,
    val config: com.ethora.chat.core.config.ChatConfig?
)

/**
 * Message props
 */
data class MessageProps(
    val message: Message,
    val isUser: Boolean,
    val isReply: Boolean
)

/**
 * Send input props
 */
data class SendInputProps(
    val onSendMessage: ((String) -> Unit)? = null,
    val onSendMedia: ((ByteArray, String) -> Unit)? = null,
    val placeholderText: String? = null,
    val messageText: String,
    val isEditing: Boolean = false,
    val editMessageId: String? = null,
    val canSend: Boolean = true
)

data class MessageActionsProps(
    val message: Message,
    val isUser: Boolean,
    val onCopy: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onDismiss: () -> Unit
)

data class RoomListItemProps(
    val room: Room,
    val isActive: Boolean,
    val onClick: () -> Unit
)

/**
 * Custom components interface
 */
interface CustomComponents {
    val customMessageComponent: (@Composable (MessageProps) -> Unit)?
    val customInputComponent: (@Composable (SendInputProps) -> Unit)?
    val customMessageActionsComponent: (@Composable (MessageActionsProps) -> Unit)?
        get() = null
    val customRoomListItemComponent: (@Composable (RoomListItemProps) -> Unit)?
        get() = null
    val customScrollableArea: (@Composable (CustomScrollableAreaProps) -> Unit)?
    val customDaySeparator: (@Composable (DaySeparatorProps) -> Unit)?
    val customNewMessageLabel: (@Composable (NewMessageLabelProps) -> Unit)?
}

/**
 * Default custom components implementation
 */
data class DefaultCustomComponents(
    override val customMessageComponent: (@Composable (MessageProps) -> Unit)? = null,
    override val customInputComponent: (@Composable (SendInputProps) -> Unit)? = null,
    override val customMessageActionsComponent: (@Composable (MessageActionsProps) -> Unit)? = null,
    override val customRoomListItemComponent: (@Composable (RoomListItemProps) -> Unit)? = null,
    override val customScrollableArea: (@Composable (CustomScrollableAreaProps) -> Unit)? = null,
    override val customDaySeparator: (@Composable (DaySeparatorProps) -> Unit)? = null,
    override val customNewMessageLabel: (@Composable (NewMessageLabelProps) -> Unit)? = null
) : CustomComponents
