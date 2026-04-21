package com.ethora.chat.core.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ChatConnectionStatus {
    OFFLINE,
    CONNECTING,
    ONLINE,
    DEGRADED,
    ERROR
}

data class ChatConnectionState(
    val status: ChatConnectionStatus = ChatConnectionStatus.OFFLINE,
    val reason: String? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val isRecovering: Boolean = false
)

object ConnectionStore {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _state = MutableStateFlow(ChatConnectionState())
    val state: StateFlow<ChatConnectionState> = _state.asStateFlow()

    private var reconnectAction: (suspend () -> Unit)? = null

    fun setState(
        status: ChatConnectionStatus,
        reason: String? = null,
        isRecovering: Boolean = false
    ) {
        _state.value = ChatConnectionState(
            status = status,
            reason = reason,
            lastUpdatedAt = System.currentTimeMillis(),
            isRecovering = isRecovering
        )
    }

    fun setReconnectAction(action: (suspend () -> Unit)?) {
        reconnectAction = action
    }

    fun reconnect() {
        val action = reconnectAction ?: return
        scope.launch {
            action()
        }
    }

    fun clear() {
        reconnectAction = null
        _state.value = ChatConnectionState()
    }
}
