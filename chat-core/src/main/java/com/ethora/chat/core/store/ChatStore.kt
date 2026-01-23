package com.ethora.chat.core.store

import com.ethora.chat.core.config.ChatConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main chat store for managing global chat state
 */
object ChatStore {
    private val _config = MutableStateFlow<ChatConfig?>(null)
    val config: StateFlow<ChatConfig?> = _config.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Set configuration
     */
    fun setConfig(config: ChatConfig?) {
        _config.value = config
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
        UserStore.clear()
        RoomStore.clear()
        MessageStore.clear()
    }
}
