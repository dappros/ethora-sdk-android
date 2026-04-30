package com.ethora.chat.internal

internal object ChatXMPPClientOwnership {
    fun shouldDisconnectOnDispose(
        chatClient: Any?,
        bootstrapClient: Any?
    ): Boolean {
        return chatClient != null && chatClient !== bootstrapClient
    }
}
