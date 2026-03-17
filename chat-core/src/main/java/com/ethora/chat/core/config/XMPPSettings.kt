package com.ethora.chat.core.config

/**
 * XMPP connection settings
 */
data class XMPPSettings(
    val xmppServerUrl: String,
    val host: String,
    val conference: String,
    val xmppPingOnSendEnabled: Boolean = true
) {
    // Backward-compatible alias used by internal components.
    val devServer: String
        get() = xmppServerUrl
}
