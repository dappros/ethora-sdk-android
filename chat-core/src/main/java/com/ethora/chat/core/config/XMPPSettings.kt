package com.ethora.chat.core.config

data class HistoryQoSSettings(
    val maxInFlightHistory: Int = 2,
    val softPauseAfterSendMs: Long = 1200L,
    val activeRoomBoostTtlMs: Long = 8000L
)

/**
 * XMPP connection settings
 */
data class XMPPSettings(
    val xmppServerUrl: String,
    val host: String,
    val conference: String,
    val xmppPingOnSendEnabled: Boolean = true,
    val historyQoS: HistoryQoSSettings = HistoryQoSSettings()
) {
    // Backward-compatible alias used by internal components.
    val devServer: String
        get() = xmppServerUrl
}
