package com.ethora.chat.core.config

import com.ethora.chat.core.models.User

/**
 * Google login configuration
 */
data class GoogleLoginConfig(
    val enabled: Boolean,
    val firebaseConfig: FirebaseConfig
)

/**
 * JWT login configuration
 * @param usePreshentStyle when true, uses /users/client + x-custom-token (preshent API).
 *   When false, uses users/login-with-jwt + Authorization (ethora API). Default: false.
 */
data class JWTLoginConfig(
    val token: String,
    val enabled: Boolean,
    val usePreshentStyle: Boolean = false
)

/**
 * User login configuration (pre-authenticated user)
 */
data class UserLoginConfig(
    val enabled: Boolean,
    val user: User?
)

/**
 * Custom login configuration with custom login function
 */
data class CustomLoginConfig(
    val enabled: Boolean,
    val loginFunction: suspend () -> User?
)
