package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.Message

/**
 * XMPP client delegate interface
 */
interface XMPPClientDelegate {
    fun onXMPPClientConnected(client: XMPPClient)
    fun onXMPPClientDisconnected(client: XMPPClient)
    fun onMessageReceived(client: XMPPClient, message: Message)
    fun onStanzaReceived(client: XMPPClient, stanza: XMPPStanza)
    fun onStatusChanged(client: XMPPClient, status: ConnectionStatus)
    fun onComposingReceived(client: XMPPClient, roomJid: String, isComposing: Boolean, composingList: List<String>)
    fun onMessageEdited(client: XMPPClient, roomJid: String, messageId: String, newText: String)
    fun onReactionReceived(client: XMPPClient, roomJid: String, messageId: String, from: String, reactions: List<String>, data: Map<String, String>)
}
