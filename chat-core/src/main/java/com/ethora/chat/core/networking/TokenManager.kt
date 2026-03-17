package com.ethora.chat.core.networking

import android.util.Log
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Token manager for handling JWT token refresh
 * Automatically refreshes tokens before expiration
 */
object TokenManager {
    private const val TAG = "TokenManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Token refresh job
    private var refreshJob: kotlinx.coroutines.Job? = null
    
    // Default token expiration time (24 hours in milliseconds)
    // In production, this should be parsed from JWT token
    private val DEFAULT_TOKEN_EXPIRATION_MS = TimeUnit.HOURS.toMillis(24)
    
    // Refresh token 5 minutes before expiration
    private val REFRESH_BUFFER_MS = TimeUnit.MINUTES.toMillis(5)
    
    /**
     * Start automatic token refresh
     * Checks token expiration and refreshes if needed
     */
    fun startAutoRefresh(baseUrl: String = ChatStore.getEffectiveBaseUrl()) {
        stopAutoRefresh()
        
        refreshJob = scope.launch {
            while (true) {
                try {
                    delay(TimeUnit.MINUTES.toMillis(10)) // Check every 10 minutes
                    
                    val refreshToken = UserStore.refreshToken.value
                    val currentToken = UserStore.token.value
                    
                    if (refreshToken == null || currentToken == null) {
                        Log.d(TAG, "⏭️ No tokens available, skipping refresh check")
                        continue
                    }
                    
                    // Check if token needs refresh
                    // In production, decode JWT to get actual expiration time
                    // For now, we'll refresh if refresh token exists and current token is old
                    val shouldRefresh = shouldRefreshToken(currentToken)
                    
                    if (shouldRefresh) {
                        Log.d(TAG, "🔄 Refreshing token...")
                        try {
                            val refreshResponse = AuthAPIHelper.refreshToken(refreshToken, baseUrl)
                            UserStore.updateTokens(refreshResponse.token, refreshResponse.refreshToken)
                            Log.d(TAG, "✅ Token refreshed successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Token refresh failed", e)
                            // If refresh fails, token might be expired - user will need to re-login
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in token refresh check", e)
                }
            }
        }
    }
    
    /**
     * Stop automatic token refresh
     */
    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }
    
    /**
     * Check if token should be refreshed
     * In production, decode JWT to check actual expiration
     * For now, use a simple heuristic
     */
    private fun shouldRefreshToken(token: String): Boolean {
        // In production, decode JWT and check expiration
        // For now, always return false to avoid unnecessary refreshes
        // The token will be refreshed when API calls fail with 401
        return false
    }
    
    /**
     * Refresh token if needed (called before API calls)
     */
    suspend fun ensureValidToken(
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): Boolean {
        val refreshToken = UserStore.refreshToken.value
        val currentToken = UserStore.token.value
        
        if (refreshToken == null || currentToken == null) {
            return false
        }
        
        // Try to refresh token
        return try {
            val refreshResponse = AuthAPIHelper.refreshToken(refreshToken, baseUrl)
            UserStore.updateTokens(refreshResponse.token, refreshResponse.refreshToken)
            Log.d(TAG, "✅ Token refreshed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Token refresh failed", e)
            false
        }
    }
}
