package com.ethora.chat.core.networking

import android.util.Log
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.models.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** Empty JSON object body for `POST users/xmpp-token`. */
class XmppTokenRequest

data class XmppTokenResponse(
    val success: Boolean? = null,
    val xmppPassword: String? = null
)

interface XmppTokenAPI {
    @POST("users/xmpp-token")
    suspend fun fetchXmppPassword(
        @Header("Authorization") authorization: String,
        @Body body: XmppTokenRequest = XmppTokenRequest()
    ): Response<XmppTokenResponse>
}

/**
 * XMPP credential recovery after a SASL `not-authorized`. Mirrors web
 * `xmppClient.authRecovery` and `xmppToken.api.ts`: the XMPP password is short
 * lived, so a reconnect needs a fresh one.
 */
object XmppCredentialRecovery {
    private const val TAG = "XmppCredentialRecovery"

    private val lock = Any()
    private var inFlight: CompletableDeferred<String?>? = null

    /**
     * `POST users/xmpp-token` with the session token. Single flight: concurrent
     * callers share one request. Writes the new password to the store and to
     * the persisted session. Never throws.
     */
    suspend fun fetchFreshXmppPassword(): String? {
        var shared: CompletableDeferred<String?>? = null
        val mine = synchronized(lock) {
            val existing = inFlight
            if (existing != null) {
                shared = existing
                null
            } else {
                CompletableDeferred<String?>().also { inFlight = it }
            }
        }
        shared?.let { return it.await() }

        val result = try {
            doFetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "xmpp-token request failed: ${e.message}")
            null
        }
        synchronized(lock) { inFlight = null }
        mine?.complete(result)
        return result
    }

    private suspend fun doFetch(): String? {
        val token = UserStore.currentUser.value?.token?.takeIf { it.isNotBlank() } ?: return null
        val api = ApiClient.createService<XmppTokenAPI>(ChatStore.getEffectiveBaseUrl())
        val response = api.fetchXmppPassword(token)
        val fresh = response.body()?.xmppPassword?.takeIf { it.isNotBlank() } ?: return null
        UserStore.applyXmppPassword(fresh)
        return fresh
    }

    /**
     * Credentials for the next XMPP connect: refresh the session first when
     * `refreshTokens` is enabled, then mint an XMPP password, then fall back to
     * the stored credentials. Returns (username, password), or null.
     */
    suspend fun recover(): Pair<String, String>? {
        if (ChatStore.getConfig()?.refreshTokens?.enabled == true) {
            val refreshed = try {
                AuthRefresh.refreshAuthTokens()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val fromRefresh = refreshed?.xmppPassword
            if (!fromRefresh.isNullOrBlank()) {
                usernameOf(UserStore.currentUser.value)?.let { return it to fromRefresh }
            }
        }

        val user = UserStore.currentUser.value
        val minted = fetchFreshXmppPassword()
        if (!minted.isNullOrBlank()) {
            usernameOf(user)?.let { return it to minted }
        }

        val stored = user?.xmppPassword
        val storedUsername = usernameOf(user)
        return if (!storedUsername.isNullOrBlank() && !stored.isNullOrBlank()) storedUsername to stored else null
    }

    /** XMPP username is `xmppUsername`, else the wallet address. */
    internal fun usernameOf(user: User?): String? =
        user?.xmppUsername?.takeIf { it.isNotBlank() } ?: user?.walletAddress?.takeIf { it.isNotBlank() }
}
