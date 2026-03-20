package com.ethora.chat.core.config

/**
 * App-level configuration (API URLs, tokens, defaults)
 */
object AppConfig {
    /**
     * Default app token (DO NOT USE IN PRODUCTION)
     */
    val defaultAppToken: String = System.getenv("ETHORA_APP_TOKEN")
        ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJkYXRhIjp7InR5cGUiOiJjbGllbnQiLCJ1c2VySWQiOiJwbGF5Z3JvdW5kLXVzZXItMSIsImFwcElkIjoiNjQ2Y2M4ZGM5NmQ0YTRkYzhmN2IyZjJkIn0sImlhdCI6MTc3Mzg1MTk2MSwiZXhwIjoxNzczODU1NTYxfQ.qCmCSfJxf-aMchgcoCuncd7KsDJ-x7GB-sQoGp7G7Mo"

    /**
     * Default app ID
     */
    const val defaultAppId: String = "646cc8dc96d4a4dc8f7b2f2d"

    /**
     * Default base URL for API calls
     */
    const val defaultBaseURL: String = "https://api.chat.ethora.com/v1"

    /**
     * Default conference domain for MUC (Multi-User Chat)
     */
    const val defaultConferenceDomain: String = "conference.xmpp.chat.ethora.com"

    /**
     * Default XMPP settings
     */
    val defaultXMPPSettings: XMPPSettings = XMPPSettings(
        xmppServerUrl = "wss://xmpp.chat.ethora.com/ws",
        host = "xmpp.chat.ethora.com",
        conference = defaultConferenceDomain,
        xmppPingOnSendEnabled = true
    )
}
