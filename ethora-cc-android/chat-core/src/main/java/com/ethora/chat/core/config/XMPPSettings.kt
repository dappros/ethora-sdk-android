package com.ethora.chat.core.config

/**
 * XMPP connection settings
 */
data class XMPPSettings(
    val devServer: String,
    val host: String,
    val conference: String,
    val xmppPingOnSendEnabled: Boolean = true
)
