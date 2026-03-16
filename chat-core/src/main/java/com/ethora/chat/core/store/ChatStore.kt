package com.ethora.chat.core.store

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.XMPPSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main chat store for managing global chat state.
 * Mirrors React: config flows from ChatWrapper and is used everywhere.
 */
object ChatStore {
    private val _config = MutableStateFlow<ChatConfig?>(null)
    val config: StateFlow<ChatConfig?> = _config.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Set configuration. Call early before any API/XMPP usage.
     * Mirrors React LoginWrapper: setBaseURL(config.baseUrl) on init.
     */
    fun setConfig(config: ChatConfig?) {
        _config.value = config
    }

    /**
     * Effective base URL: config.baseUrl or AppConfig default.
     * Use this everywhere instead of hardcoded AppConfig.defaultBaseURL.
     */
    fun getEffectiveBaseUrl(): String = _config.value?.baseUrl ?: AppConfig.defaultBaseURL

    /**
     * Effective app ID: config.appId or AppConfig default.
     */
    fun getEffectiveAppId(): String = _config.value?.appId ?: AppConfig.defaultAppId

    /**
     * Effective conference domain from xmppSettings.
     */
    fun getEffectiveConference(): String = _config.value?.xmppSettings?.conference ?: AppConfig.defaultConferenceDomain

    /**
     * Effective XMPP settings: config.xmppSettings or AppConfig default.
     */
    fun getEffectiveXmppSettings(): XMPPSettings = _config.value?.xmppSettings ?: AppConfig.defaultXMPPSettings

    /**
     * Set initialized state
     */
    fun setInitialized(initialized: Boolean) {
        _isInitialized.value = initialized
    }

    /**
     * Clear all state
     */
    fun clear() {
        _config.value = null
        _isInitialized.value = false
        UserStore.clear()
        RoomStore.clear()
        MessageStore.clear()
    }
}
