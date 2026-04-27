package com.ethora.chat.core.config

/**
 * App-level configuration (API URLs, tokens, defaults)
 */
object AppConfig {
    /**
     * Optional environment-provided app token. The SDK does not embed a
     * fallback token; hosts must provide production credentials.
     */
    val defaultAppToken: String = System.getenv("ETHORA_APP_TOKEN")
        ?: ""

    @Deprecated("No SDK default app ID is used. Configure ChatConfig.appId explicitly.")
    const val defaultAppId: String = ""

    @Deprecated("No SDK default API URL is used. Configure ChatConfig.baseUrl explicitly.")
    const val defaultBaseURL: String = ""

    @Deprecated("No SDK default conference domain is used. Configure ChatConfig.xmppSettings explicitly.")
    const val defaultConferenceDomain: String = ""

    @Deprecated("No SDK default XMPP endpoint is used. Configure ChatConfig.xmppSettings explicitly.")
    val defaultXMPPSettings: XMPPSettings = XMPPSettings(
        xmppServerUrl = "",
        host = "",
        conference = "",
        xmppPingOnSendEnabled = true
    )
}
