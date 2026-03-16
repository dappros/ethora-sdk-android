package com.ethora.chat.core.config

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.CustomComponents

/**
 * Main chat configuration class mirroring IConfig from web and ChatConfig from Swift
 * Contains all configuration options available in the web version
 */
data class ChatConfig(
    // Basic UI Settings
    val disableHeader: Boolean? = null,
    val disableMedia: Boolean? = null,
    val colors: ChatColors? = null,

    // Login Configurations
    val googleLogin: GoogleLoginConfig? = null,
    val jwtLogin: JWTLoginConfig? = null,
    val userLogin: UserLoginConfig? = null,
    val customLogin: CustomLoginConfig? = null,

    // API & XMPP Settings (mirrors React IConfig)
    val appId: String? = null,
    val baseUrl: String? = null,
    val customAppToken: String? = null,
    val xmppSettings: XMPPSettings? = null,

    // Room Settings
    val disableRooms: Boolean? = null,
    val defaultLogin: Boolean? = null,
    val disableInteractions: Boolean? = null,
    val chatHeaderBurgerMenu: Boolean? = null,
    val forceSetRoom: Boolean? = null,
    val setRoomJidInPath: Boolean? = null,
    val disableRoomMenu: Boolean? = null,
    val disableRoomConfig: Boolean? = null,
    val disableNewChatButton: Boolean? = null,
    val defaultRooms: List<Room>? = null,
    val customRooms: CustomRoomsConfig? = null,

    // Styling
    val roomListStyles: Map<String, Any>? = null,
    val chatRoomStyles: Map<String, Any>? = null,
    val backgroundChat: BackgroundChatConfig? = null,
    val bubleMessage: MessageBubbleStyle? = null,

    // Header
    val headerLogo: String? = null,
    val headerMenu: (() -> Unit)? = null,
    val headerChatMenu: (() -> Unit)? = null,

    // Tokens & Auth
    val refreshTokens: RefreshTokensConfig? = null,

    // Translations
    val translates: TranslationsConfig? = null,

    // Features
    val disableProfilesInteractions: Boolean? = null,
    val disableUserCount: Boolean? = null,
    val clearStoreBeforeInit: Boolean? = null,
    val disableSentLogic: Boolean? = null,
    val initBeforeLoad: Boolean? = null,
    val newArch: Boolean? = null,
    val qrUrl: String? = null,

    // Secondary Send Button
    val secondarySendButton: SecondarySendButtonConfig? = null,

    // Retry
    val enableRoomsRetry: EnableRoomsRetryConfig? = null,

    // Chat Header Additional
    val chatHeaderAdditional: ChatHeaderAdditionalConfig? = null,

    // Bot & Messages
    val botMessageAutoScroll: Boolean? = null,
    val messageTextFilter: MessageTextFilterConfig? = null,
    val whitelistSystemMessage: List<String>? = null,
    val customSystemMessage: ((com.ethora.chat.core.models.MessageProps) -> Unit)? = null,

    // Typing Indicator
    val disableTypingIndicator: Boolean? = null,
    val customTypingIndicator: CustomTypingIndicatorConfig? = null,

    // Block Message Sending
    val blockMessageSendingWhenProcessing: BlockMessageSendingConfig? = null,

    // Chat Info
    val disableChatInfo: DisableChatInfoConfig? = null,
    val chatHeaderSettings: ChatHeaderSettingsConfig? = null,

    // Store
    val useStoreConsoleEnabled: Boolean? = null,

    // Notifications
    val messageNotifications: MessageNotificationConfig? = null,

    // Event Handlers
    val eventHandlers: ChatEventHandlers? = null,

    // Custom Components
    val customComponents: CustomComponents? = null
) {
    /**
     * Builder class for creating ChatConfig instances
     */
    class Builder {
        private var disableHeader: Boolean? = null
        private var disableMedia: Boolean? = null
        private var colors: ChatColors? = null
        private var googleLogin: GoogleLoginConfig? = null
        private var jwtLogin: JWTLoginConfig? = null
        private var userLogin: UserLoginConfig? = null
        private var customLogin: CustomLoginConfig? = null
        private var appId: String? = null
        private var baseUrl: String? = null
        private var customAppToken: String? = null
        private var xmppSettings: XMPPSettings? = null
        private var disableRooms: Boolean? = null
        private var defaultLogin: Boolean? = null
        private var disableInteractions: Boolean? = null
        private var chatHeaderBurgerMenu: Boolean? = null
        private var forceSetRoom: Boolean? = null
        private var setRoomJidInPath: Boolean? = null
        private var disableRoomMenu: Boolean? = null
        private var disableRoomConfig: Boolean? = null
        private var disableNewChatButton: Boolean? = null
        private var defaultRooms: List<Room>? = null
        private var customRooms: CustomRoomsConfig? = null
        private var roomListStyles: Map<String, Any>? = null
        private var chatRoomStyles: Map<String, Any>? = null
        private var backgroundChat: BackgroundChatConfig? = null
        private var bubleMessage: MessageBubbleStyle? = null
        private var headerLogo: String? = null
        private var headerMenu: (() -> Unit)? = null
        private var headerChatMenu: (() -> Unit)? = null
        private var refreshTokens: RefreshTokensConfig? = null
        private var translates: TranslationsConfig? = null
        private var disableProfilesInteractions: Boolean? = null
        private var disableUserCount: Boolean? = null
        private var clearStoreBeforeInit: Boolean? = null
        private var disableSentLogic: Boolean? = null
        private var initBeforeLoad: Boolean? = null
        private var newArch: Boolean? = null
        private var qrUrl: String? = null
        private var secondarySendButton: SecondarySendButtonConfig? = null
        private var enableRoomsRetry: EnableRoomsRetryConfig? = null
        private var chatHeaderAdditional: ChatHeaderAdditionalConfig? = null
        private var botMessageAutoScroll: Boolean? = null
        private var messageTextFilter: MessageTextFilterConfig? = null
        private var whitelistSystemMessage: List<String>? = null
        private var customSystemMessage: ((com.ethora.chat.core.models.MessageProps) -> Unit)? = null
        private var disableTypingIndicator: Boolean? = null
        private var customTypingIndicator: CustomTypingIndicatorConfig? = null
        private var blockMessageSendingWhenProcessing: BlockMessageSendingConfig? = null
        private var disableChatInfo: DisableChatInfoConfig? = null
        private var chatHeaderSettings: ChatHeaderSettingsConfig? = null
        private var useStoreConsoleEnabled: Boolean? = null
        private var messageNotifications: MessageNotificationConfig? = null
        private var eventHandlers: ChatEventHandlers? = null
        private var customComponents: CustomComponents? = null

        fun disableHeader(value: Boolean) = apply { this.disableHeader = value }
        fun disableMedia(value: Boolean) = apply { this.disableMedia = value }
        fun colors(value: ChatColors) = apply { this.colors = value }
        fun googleLogin(value: GoogleLoginConfig) = apply { this.googleLogin = value }
        fun jwtLogin(value: JWTLoginConfig) = apply { this.jwtLogin = value }
        fun userLogin(value: UserLoginConfig) = apply { this.userLogin = value }
        fun customLogin(value: CustomLoginConfig) = apply { this.customLogin = value }
        fun appId(value: String) = apply { this.appId = value }
        fun baseUrl(value: String) = apply { this.baseUrl = value }
        fun customAppToken(value: String) = apply { this.customAppToken = value }
        fun xmppSettings(value: XMPPSettings) = apply { this.xmppSettings = value }
        fun disableRooms(value: Boolean) = apply { this.disableRooms = value }
        fun defaultLogin(value: Boolean) = apply { this.defaultLogin = value }
        fun disableInteractions(value: Boolean) = apply { this.disableInteractions = value }
        fun chatHeaderBurgerMenu(value: Boolean) = apply { this.chatHeaderBurgerMenu = value }
        fun forceSetRoom(value: Boolean) = apply { this.forceSetRoom = value }
        fun setRoomJidInPath(value: Boolean) = apply { this.setRoomJidInPath = value }
        fun disableRoomMenu(value: Boolean) = apply { this.disableRoomMenu = value }
        fun disableRoomConfig(value: Boolean) = apply { this.disableRoomConfig = value }
        fun disableNewChatButton(value: Boolean) = apply { this.disableNewChatButton = value }
        fun defaultRooms(value: List<Room>) = apply { this.defaultRooms = value }
        fun customRooms(value: CustomRoomsConfig) = apply { this.customRooms = value }
        fun roomListStyles(value: Map<String, Any>) = apply { this.roomListStyles = value }
        fun chatRoomStyles(value: Map<String, Any>) = apply { this.chatRoomStyles = value }
        fun backgroundChat(value: BackgroundChatConfig) = apply { this.backgroundChat = value }
        fun bubleMessage(value: MessageBubbleStyle) = apply { this.bubleMessage = value }
        fun headerLogo(value: String) = apply { this.headerLogo = value }
        fun headerMenu(value: () -> Unit) = apply { this.headerMenu = value }
        fun headerChatMenu(value: () -> Unit) = apply { this.headerChatMenu = value }
        fun refreshTokens(value: RefreshTokensConfig) = apply { this.refreshTokens = value }
        fun translates(value: TranslationsConfig) = apply { this.translates = value }
        fun disableProfilesInteractions(value: Boolean) = apply { this.disableProfilesInteractions = value }
        fun disableUserCount(value: Boolean) = apply { this.disableUserCount = value }
        fun clearStoreBeforeInit(value: Boolean) = apply { this.clearStoreBeforeInit = value }
        fun disableSentLogic(value: Boolean) = apply { this.disableSentLogic = value }
        fun initBeforeLoad(value: Boolean) = apply { this.initBeforeLoad = value }
        fun newArch(value: Boolean) = apply { this.newArch = value }
        fun qrUrl(value: String) = apply { this.qrUrl = value }
        fun secondarySendButton(value: SecondarySendButtonConfig) = apply { this.secondarySendButton = value }
        fun enableRoomsRetry(value: EnableRoomsRetryConfig) = apply { this.enableRoomsRetry = value }
        fun chatHeaderAdditional(value: ChatHeaderAdditionalConfig) = apply { this.chatHeaderAdditional = value }
        fun botMessageAutoScroll(value: Boolean) = apply { this.botMessageAutoScroll = value }
        fun messageTextFilter(value: MessageTextFilterConfig) = apply { this.messageTextFilter = value }
        fun whitelistSystemMessage(value: List<String>) = apply { this.whitelistSystemMessage = value }
        fun customSystemMessage(value: (com.ethora.chat.core.models.MessageProps) -> Unit) = apply { this.customSystemMessage = value }
        fun disableTypingIndicator(value: Boolean) = apply { this.disableTypingIndicator = value }
        fun customTypingIndicator(value: CustomTypingIndicatorConfig) = apply { this.customTypingIndicator = value }
        fun blockMessageSendingWhenProcessing(value: BlockMessageSendingConfig) = apply { this.blockMessageSendingWhenProcessing = value }
        fun disableChatInfo(value: DisableChatInfoConfig) = apply { this.disableChatInfo = value }
        fun chatHeaderSettings(value: ChatHeaderSettingsConfig) = apply { this.chatHeaderSettings = value }
        fun useStoreConsoleEnabled(value: Boolean) = apply { this.useStoreConsoleEnabled = value }
        fun messageNotifications(value: MessageNotificationConfig) = apply { this.messageNotifications = value }
        fun eventHandlers(value: ChatEventHandlers) = apply { this.eventHandlers = value }
        fun customComponents(value: CustomComponents) = apply { this.customComponents = value }

        fun build() = ChatConfig(
            disableHeader = disableHeader,
            disableMedia = disableMedia,
            colors = colors,
            googleLogin = googleLogin,
            jwtLogin = jwtLogin,
            userLogin = userLogin,
            customLogin = customLogin,
            appId = appId,
            baseUrl = baseUrl,
            customAppToken = customAppToken,
            xmppSettings = xmppSettings,
            disableRooms = disableRooms,
            defaultLogin = defaultLogin,
            disableInteractions = disableInteractions,
            chatHeaderBurgerMenu = chatHeaderBurgerMenu,
            forceSetRoom = forceSetRoom,
            setRoomJidInPath = setRoomJidInPath,
            disableRoomMenu = disableRoomMenu,
            disableRoomConfig = disableRoomConfig,
            disableNewChatButton = disableNewChatButton,
            defaultRooms = defaultRooms,
            customRooms = customRooms,
            roomListStyles = roomListStyles,
            chatRoomStyles = chatRoomStyles,
            backgroundChat = backgroundChat,
            bubleMessage = bubleMessage,
            headerLogo = headerLogo,
            headerMenu = headerMenu,
            headerChatMenu = headerChatMenu,
            refreshTokens = refreshTokens,
            translates = translates,
            disableProfilesInteractions = disableProfilesInteractions,
            disableUserCount = disableUserCount,
            clearStoreBeforeInit = clearStoreBeforeInit,
            disableSentLogic = disableSentLogic,
            initBeforeLoad = initBeforeLoad,
            newArch = newArch,
            qrUrl = qrUrl,
            secondarySendButton = secondarySendButton,
            enableRoomsRetry = enableRoomsRetry,
            chatHeaderAdditional = chatHeaderAdditional,
            botMessageAutoScroll = botMessageAutoScroll,
            messageTextFilter = messageTextFilter,
            whitelistSystemMessage = whitelistSystemMessage,
            customSystemMessage = customSystemMessage,
            disableTypingIndicator = disableTypingIndicator,
            customTypingIndicator = customTypingIndicator,
            blockMessageSendingWhenProcessing = blockMessageSendingWhenProcessing,
            disableChatInfo = disableChatInfo,
            chatHeaderSettings = chatHeaderSettings,
            useStoreConsoleEnabled = useStoreConsoleEnabled,
            messageNotifications = messageNotifications,
            eventHandlers = eventHandlers,
            customComponents = customComponents
        )
    }

    companion object {
        fun builder() = Builder()
    }
}
