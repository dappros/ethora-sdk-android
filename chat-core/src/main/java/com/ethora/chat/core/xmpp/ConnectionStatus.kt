package com.ethora.chat.core.xmpp

/**
 * XMPP connection status
 */
enum class ConnectionStatus {
    OFFLINE,
    CONNECTING,
    ONLINE,
    DISCONNECTING,
    ERROR
}
