package com.ethora.chat.core.store

import com.ethora.chat.core.models.User
import com.ethora.chat.core.persistence.ChatPersistenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * User store for managing current user state
 * Persists user to DataStore (matches web: redux-persist with localStorage)
 */
object UserStore {
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()
    
    // Selected user for profile view
    private val _selectedUser = MutableStateFlow<User?>(null)
    val selectedUser: StateFlow<User?> = _selectedUser.asStateFlow()
    
    // Persistence manager
    private var persistenceManager: ChatPersistenceManager? = null
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Initialize with persistence manager
     */
    fun initialize(persistence: ChatPersistenceManager) {
        persistenceManager = persistence
        android.util.Log.d("UserStore", "✅ UserStore initialized with ChatPersistenceManager")
    }
    
    /**
     * Load user from persistence
     */
    suspend fun loadUserFromPersistence(): User? {
        return try {
            val persistence = persistenceManager
            if (persistence != null) {
                val persistedUser = persistence.loadUser()
                val (token, refreshToken) = persistence.loadTokens()
                if (persistedUser != null) {
                    val userWithTokens = persistedUser.copy(
                        token = token,
                        refreshToken = refreshToken
                    )
                    android.util.Log.d("UserStore", "📂 Loaded user from persistence")
                    userWithTokens
                } else {
                    null
                }
            } else {
                android.util.Log.w("UserStore", "⚠️ ChatPersistenceManager not initialized, cannot load from persistence")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("UserStore", "❌ Error loading user from persistence", e)
            null
        }
    }
    
    /**
     * Persist user (background, non-blocking)
     */
    private fun persistUser() {
        val persistence = persistenceManager ?: return
        persistenceScope.launch {
            try {
                persistence.saveUser(_currentUser.value)
            } catch (e: Exception) {
                android.util.Log.e("UserStore", "❌ Error persisting user", e)
            }
        }
    }
    
    /**
     * Persist tokens (background, non-blocking)
     */
    private fun persistTokens() {
        val persistence = persistenceManager ?: return
        persistenceScope.launch {
            try {
                persistence.saveTokens(_token.value, _refreshToken.value)
            } catch (e: Exception) {
                android.util.Log.e("UserStore", "❌ Error persisting tokens", e)
            }
        }
    }
    
    /**
     * Set selected user (for profile view)
     */
    fun setSelectedUser(user: User?) {
        _selectedUser.value = user
    }
    
    /**
     * Clear selected user
     */
    fun clearSelectedUser() {
        _selectedUser.value = null
    }

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _refreshToken = MutableStateFlow<String?>(null)
    val refreshToken: StateFlow<String?> = _refreshToken.asStateFlow()

    /**
     * Set current user
     */
    fun setUser(user: User?) {
        _currentUser.value = user
        // Persist user (background)
        persistUser()
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
        // Persist user and tokens (background)
        persistUser()
        persistTokens()
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
        // Keep current user in sync so persisted user object remains valid across restarts.
        _currentUser.value = _currentUser.value?.copy(
            token = token,
            refreshToken = refreshToken ?: _currentUser.value?.refreshToken
        )
        // Persist tokens (background)
        persistTokens()
        // Persist user (background) so token fields stay consistent on restore.
        persistUser()
    }

    /**
     * Clear user data
     */
    fun clear() {
        _currentUser.value = null
        _token.value = null
        _refreshToken.value = null
        _selectedUser.value = null
        // Persist cleared state (background)
        persistenceScope.launch {
            persistenceManager?.saveUser(null)
            persistenceManager?.saveTokens(null, null)
        }
    }
    
    /**
     * Get persistence manager (for LogoutService)
     */
    internal fun getPersistenceManager(): ChatPersistenceManager? {
        return persistenceManager
    }
}
