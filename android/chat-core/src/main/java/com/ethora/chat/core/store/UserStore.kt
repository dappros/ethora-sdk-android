package com.ethora.chat.core.store

import com.ethora.chat.core.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User store for managing current user state
 */
object UserStore {
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _refreshToken = MutableStateFlow<String?>(null)
    val refreshToken: StateFlow<String?> = _refreshToken.asStateFlow()

    /**
     * Set current user
     */
    fun setUser(user: User?) {
        _currentUser.value = user
    }

    /**
     * Set user from login response
     */
    fun setUserFromLoginResponse(
        user: User,
        token: String,
        refreshToken: String
    ) {
        _currentUser.value = user.copy(token = token, refreshToken = refreshToken)
        _token.value = token
        _refreshToken.value = refreshToken
    }
    
    /**
     * Set user from login response (convenience method)
     */
    fun setUser(loginResponse: com.ethora.chat.core.networking.LoginResponse) {
        val user = loginResponse.user.toUser().copy(
            token = loginResponse.token,
            refreshToken = loginResponse.refreshToken
        )
        setUserFromLoginResponse(user, loginResponse.token, loginResponse.refreshToken)
    }

    /**
     * Update tokens
     */
    fun updateTokens(token: String, refreshToken: String?) {
        _token.value = token
        refreshToken?.let { _refreshToken.value = it }
    }

    /**
     * Clear user data
     */
    fun clear() {
        _currentUser.value = null
        _token.value = null
        _refreshToken.value = null
    }
}
