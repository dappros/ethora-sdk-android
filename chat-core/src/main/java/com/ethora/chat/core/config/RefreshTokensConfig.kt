package com.ethora.chat.core.config

/**
 * Refresh tokens configuration
 */
data class RefreshTokensConfig(
    val enabled: Boolean,
    val refreshFunction: (suspend () -> TokenRefreshResult?)? = null
)

/**
 * Token refresh result
 */
data class TokenRefreshResult(
    val accessToken: String,
    val refreshToken: String? = null
)
