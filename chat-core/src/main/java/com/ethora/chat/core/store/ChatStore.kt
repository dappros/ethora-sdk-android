package com.ethora.chat.core.store

import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.XMPPSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

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
     * Get current config (for auth style etc.)
     */
    fun getConfig(): ChatConfig? = _config.value

    fun validateServerConfig(config: ChatConfig?): String? {
        val baseUrl = config?.baseUrl?.trim()
        if (baseUrl.isNullOrBlank()) return "ChatConfig.baseUrl is required"
        if (!isHttpUrl(baseUrl)) return "ChatConfig.baseUrl must be a valid http(s) URL"

        val appId = config.appId?.trim()
        if (appId.isNullOrBlank()) return "ChatConfig.appId is required"

        val xmpp = config.xmppSettings ?: return "ChatConfig.xmppSettings is required"
        if (xmpp.xmppServerUrl.isBlank()) return "ChatConfig.xmppSettings.xmppServerUrl is required"
        if (!xmpp.xmppServerUrl.startsWith("ws://") && !xmpp.xmppServerUrl.startsWith("wss://")) {
            return "ChatConfig.xmppSettings.xmppServerUrl must start with ws:// or wss://"
        }
        if (xmpp.host.isBlank()) return "ChatConfig.xmppSettings.host is required"
        if (xmpp.conference.isBlank()) return "ChatConfig.xmppSettings.conference is required"
        return null
    }

    fun requireValidServerConfig(config: ChatConfig? = _config.value) {
        validateServerConfig(config)?.let { throw IllegalStateException(it) }
    }

    fun getEffectiveBaseUrl(): String {
        val config = _config.value
        validateServerConfig(config)?.let { throw IllegalStateException(it) }
        return config?.baseUrl!!.trim()
    }

    /**
     * Effective app ID: config.appId or AppConfig default.
     */
    fun getEffectiveAppId(): String {
        val config = _config.value
        validateServerConfig(config)?.let { throw IllegalStateException(it) }
        return config?.appId!!.trim()
    }

    /**
     * Effective conference domain from xmppSettings.
     */
    fun getEffectiveConference(): String {
        val config = _config.value
        validateServerConfig(config)?.let { throw IllegalStateException(it) }
        return config?.xmppSettings!!.conference.trim()
    }

    /**
     * Effective XMPP settings: config.xmppSettings or AppConfig default.
     */
    fun getEffectiveXmppSettings(): XMPPSettings {
        val config = _config.value
        validateServerConfig(config)?.let { throw IllegalStateException(it) }
        return config?.xmppSettings!!
    }

    private fun isHttpUrl(value: String): Boolean {
        return runCatching {
            val uri = URI(value)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

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
        ConnectionStore.clear()
        UserStore.clear()
        RoomStore.clear()
        MessageStore.clear()
    }
}
