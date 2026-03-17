package com.ethora.test.app

import androidx.compose.runtime.Composable
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig

@Composable
fun ChatTab(config: ChatConfig) {
    Chat(config = config, roomJID = null)
}
