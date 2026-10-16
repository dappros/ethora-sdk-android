package com.ethora.chat.core.config

/**
 * Push notification configuration, mirroring `pushNotifications` in the web
 * and React Native SDKs. Push subscription is OFF unless [enabled] is true.
 */
data class PushNotificationsConfig(
    val enabled: Boolean = false,
    /**
     * Optional push gateway base URL, e.g. `https://push.example.com/api/v1`.
     * When set, device subscriptions go to `{apiUrl}/subscriptions` instead of
     * the Ethora API `push/subscription/{appId}` endpoint.
     */
    val apiUrl: String? = null,
    /** Required together with [apiUrl]: project name sent to the gateway. */
    val projectName: String? = null,
    val iconPath: String? = null,
    val badgePath: String? = null
)
