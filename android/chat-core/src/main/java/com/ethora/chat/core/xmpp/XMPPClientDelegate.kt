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
}
