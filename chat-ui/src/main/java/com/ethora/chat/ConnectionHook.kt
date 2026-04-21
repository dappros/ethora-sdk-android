package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ethora.chat.core.store.ChatConnectionState
import com.ethora.chat.core.store.ConnectionStore

@Composable
fun useConnectionState(): ChatConnectionState {
    val state by ConnectionStore.state.collectAsState()
    return state
}

fun reconnectChat() {
    ConnectionStore.reconnect()
}
