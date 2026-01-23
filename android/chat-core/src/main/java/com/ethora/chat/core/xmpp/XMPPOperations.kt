package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * XMPP operations helper class
 */
object XMPPOperations {
    /**
     * Send text message
     */
    suspend fun sendTextMessage(
        client: XMPPClient,
        roomJID: String,
        messageBody: String
    ): Boolean = withContext(Dispatchers.IO) {
        client.sendMessage(roomJID, messageBody)
    }

    /**
     * Join room
     */
    suspend fun joinRoom(
        client: XMPPClient,
        roomJID: String,
        nickname: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        client.joinRoom(roomJID, nickname)
    }

    /**
     * Leave room
     */
    suspend fun leaveRoom(
        client: XMPPClient,
        roomJID: String
    ): Boolean = withContext(Dispatchers.IO) {
        client.leaveRoom(roomJID)
    }

    /**
     * Send typing indicator
     */
    suspend fun sendTypingIndicator(
        client: XMPPClient,
        roomJID: String,
        isTyping: Boolean
    ) = withContext(Dispatchers.IO) {
        client.sendTypingIndicator(roomJID, isTyping)
    }
}
