package com.ethora.chat.core.networking

import android.util.Log
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Proactive token refresh. Reads the access token's JWT `exp` claim and rotates
 * the session through [AuthRefresh] shortly before it expires. Requests that
 * still hit a 401 are refreshed reactively by [ApiClient].
 */
object TokenManager {
    private const val TAG = "TokenManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var refreshJob: kotlinx.coroutines.Job? = null

    /** How often the expiry is checked. */
    private val CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(60)

    /** Refresh once the token expires within this window. */
    private val REFRESH_BUFFER_MS = TimeUnit.MINUTES.toMillis(5)

    /**
     * Start the background expiry check. [baseUrl] is kept for source
     * compatibility; the endpoint comes from [ChatStore].
     */
    @Suppress("UNUSED_PARAMETER")
    fun startAutoRefresh(baseUrl: String = ChatStore.getEffectiveBaseUrl()) {
        stopAutoRefresh()
        refreshJob = scope.launch {
            while (isActive) {
                try {
                    delay(CHECK_INTERVAL_MS)
                    if (shouldRefreshToken(UserStore.token.value)) {
                        Log.d(TAG, "Access token near expiry, refreshing")
                        if (AuthRefresh.refreshAuthTokensQuietly() != null) {
                            Log.d(TAG, "Token refreshed")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in token expiry check", e)
                }
            }
        }
    }

    /** Stop the background expiry check. */
    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * True when [token] expires within the refresh buffer, or has expired.
     * A token without a readable `exp` is not refreshed proactively.
     */
    internal fun shouldRefreshToken(token: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val remaining = JwtClaims.millisUntilExpiry(token, nowMillis) ?: return false
        return remaining <= REFRESH_BUFFER_MS
    }

    /**
     * Make sure the access token is fresh before a call. Returns false when no
     * session can be refreshed.
     */
    suspend fun ensureValidToken(
        @Suppress("UNUSED_PARAMETER") baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): Boolean {
        if (!AuthRefresh.hasRotatableSession()) return false
        if (!shouldRefreshToken(UserStore.token.value)) return true
        return AuthRefresh.refreshAuthTokensQuietly() != null
    }
}
