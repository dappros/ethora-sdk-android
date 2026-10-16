package com.ethora.chat.core.networking

import android.util.Log
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Refresh-token error codes returned by `POST users/login/refresh` (HTTP 401).
 * Mirrors web `authRefresh.ts`.
 */
object RefreshErrorCode {
    const val IN_PROGRESS = "REFRESH_IN_PROGRESS"
    const val ALREADY_ROTATED = "REFRESH_TOKEN_ALREADY_ROTATED"
    const val REUSE_DETECTED = "REFRESH_TOKEN_REUSE_DETECTED"
    const val NOT_FOUND = "REFRESH_TOKEN_NOT_FOUND"

    internal val ALL = listOf(IN_PROGRESS, ALREADY_ROTATED, REUSE_DETECTED, NOT_FOUND)
}

/**
 * The session is genuinely dead. The caller must log the user out. Every other
 * refresh failure (network, 5xx, plain 401) is NOT fatal and must not log out.
 */
class RefreshFatalError(val code: String, message: String? = null) :
    Exception(message ?: "Refresh failed: $code")

/** Wire-level refresh failure carrying the parsed error code, if any. */
class RefreshHttpException(val code: String?, message: String) : Exception(message)

/** Tokens produced by a successful rotation (or by a host refresh function). */
data class RefreshedSession(
    val token: String,
    val refreshToken: String,
    val fileToken: String? = null,
    val xmppPassword: String? = null
)

/**
 * Find the refresh error code in an error body or the `x-error-code` header.
 * The backend's exact payload location is not fixed yet, so every shape the
 * API uses elsewhere is probed, the same as web.
 */
internal fun parseRefreshErrorCode(body: String?, headerCode: String? = null): String? {
    val candidates = mutableListOf<String?>(headerCode)
    if (!body.isNullOrBlank()) {
        val root = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            null
        }
        if (root != null && root.isJsonObject) {
            val obj = root.asJsonObject
            candidates += obj.stringOf("code")
            candidates += obj.objectOf("error")?.stringOf("code")
            candidates += obj.arrayFirstObjectCode("errors")
            candidates += obj.stringOf("error")
            candidates += obj.stringOf("message")
        }
        candidates += body
    }
    for (candidate in candidates) {
        if (candidate == null) continue
        val match = RefreshErrorCode.ALL.firstOrNull { candidate.contains(it) }
        if (match != null) return match
    }
    return null
}

private fun JsonObject.stringOf(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.objectOf(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayFirstObjectCode(key: String): String? {
    val array = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val first = array.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return first.stringOf("code")
}

/**
 * The single refresh-token rotation point for the Android SDK.
 *
 * The backend rotates refresh tokens with reuse detection: every success burns
 * the presented token. So the rules are:
 *  1. Persist the NEW refresh token before returning (awaited, see [UserStore.applyRotatedSession]).
 *  2. Never rotate concurrently. A [Mutex] serialises callers, and a caller that
 *     waited re-reads the stored token and adopts a rotation that already happened.
 *  3. Route every refresh through here.
 *
 * Callers must not treat a 401 as "log out": only [RefreshFatalError] means that.
 */
object AuthRefresh {
    private const val TAG = "AuthRefresh"
    private const val IN_PROGRESS_MAX_ATTEMPTS = 3
    private const val IN_PROGRESS_BASE_DELAY_MS = 300L
    private const val IN_PROGRESS_JITTER_MS = 150

    private val lock = Mutex()

    /** Refresh token of a session that was declared dead. Cleared on the next success. */
    @Volatile
    private var deadRefreshToken: String? = null

    /** Test seam: the wire call. Production uses [defaultRotate]. */
    internal var rotateWithEndpoint: suspend (String) -> RefreshedSession = { presented -> defaultRotate(presented) }

    /** Test seam: the back-off between `REFRESH_IN_PROGRESS` retries. */
    internal var retryDelay: suspend (Long) -> Unit = { ms -> delay(ms) }

    /** The refresh token as of now, read from the store. */
    fun currentRefreshToken(): String? =
        UserStore.refreshToken.value?.takeIf { it.isNotBlank() }
            ?: UserStore.currentUser.value?.refreshToken?.takeIf { it.isNotBlank() }

    /** Is there anything left to rotate (a host refresh function or a refresh token)? */
    fun hasRotatableSession(): Boolean =
        ChatStore.getConfig()?.refreshTokens?.refreshFunction != null || !currentRefreshToken().isNullOrBlank()

    /**
     * Rotate the tokens. Concurrent callers are serialised; a caller that
     * queued behind a rotation adopts its result instead of rotating again.
     * Throws [RefreshFatalError] when the session is dead.
     */
    suspend fun refreshAuthTokens(): RefreshedSession {
        // Read BEFORE waiting, so performRefresh can tell "nothing changed" from
        // "another caller rotated while I waited".
        val presentedBeforeLock = currentRefreshToken()
        return lock.withLock { performRefresh(presentedBeforeLock) }
    }

    /** Variant that never throws. Returns null when the refresh did not succeed. */
    suspend fun refreshAuthTokensQuietly(): RefreshedSession? =
        try {
            refreshAuthTokens()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background token refresh failed: ${e.message}")
            null
        }

    /** Latch the current session as dead after a [RefreshFatalError]. */
    fun markSessionDead() {
        deadRefreshToken = currentRefreshToken().orEmpty()
    }

    /** True while the current refresh token is the one latched as dead. */
    fun isSessionDead(): Boolean {
        val dead = deadRefreshToken ?: return false
        return dead == currentRefreshToken().orEmpty()
    }

    private suspend fun performRefresh(presentedBeforeLock: String?): RefreshedSession {
        val hostRefresh = ChatStore.getConfig()?.refreshTokens?.refreshFunction
        if (hostRefresh != null) {
            // A host-owned refresh replaces the built-in endpoint. The SDK never
            // rotates the host's token itself.
            val result = hostRefresh() ?: throw IllegalStateException("Custom refresh function returned no session")
            if (result.accessToken.isBlank()) {
                throw IllegalStateException("Custom refresh function did not return an access token")
            }
            val session = RefreshedSession(
                token = result.accessToken,
                refreshToken = result.refreshToken ?: currentRefreshToken().orEmpty(),
                fileToken = result.fileToken,
                xmppPassword = result.xmppPassword
            )
            return persist(session)
        }

        // Another caller rotated while we waited: use its result.
        val current = currentRefreshToken()
        if (presentedBeforeLock != null && current != null && current != presentedBeforeLock) {
            adoptCurrent()?.let { return it }
        }

        for (attempt in 1..IN_PROGRESS_MAX_ATTEMPTS) {
            val presented = currentRefreshToken() ?: throw IllegalStateException("Refresh token is missing")
            try {
                return persist(rotateWithEndpoint(presented))
            } catch (e: RefreshHttpException) {
                when (e.code) {
                    RefreshErrorCode.IN_PROGRESS -> {
                        if (attempt < IN_PROGRESS_MAX_ATTEMPTS) {
                            retryDelay(IN_PROGRESS_BASE_DELAY_MS * attempt + jitterMs())
                            continue
                        }
                        // Budget spent: whoever holds the rotation has probably written a newer token.
                        adoptIfNewer(presented)?.let { return it }
                        throw e
                    }
                    RefreshErrorCode.ALREADY_ROTATED -> {
                        adoptIfNewer(presented)?.let { return it }
                        throw RefreshFatalError(e.code)
                    }
                    RefreshErrorCode.REUSE_DETECTED, RefreshErrorCode.NOT_FOUND -> {
                        throw RefreshFatalError(e.code ?: RefreshErrorCode.NOT_FOUND)
                    }
                    else -> throw e
                }
            }
        }
        throw IllegalStateException("Refresh attempts exhausted")
    }

    private fun jitterMs(): Long = (0..IN_PROGRESS_JITTER_MS).random().toLong()

    private fun adoptIfNewer(presented: String): RefreshedSession? {
        val current = currentRefreshToken() ?: return null
        return if (current != presented) adoptCurrent() else null
    }

    private fun adoptCurrent(): RefreshedSession? {
        val token = UserStore.token.value?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = currentRefreshToken() ?: return null
        val user = UserStore.currentUser.value
        return RefreshedSession(
            token = token,
            refreshToken = refreshToken,
            fileToken = user?.fileToken,
            xmppPassword = user?.xmppPassword
        )
    }

    /** Persist before returning, then clear any dead-session latch. */
    private suspend fun persist(session: RefreshedSession): RefreshedSession {
        UserStore.applyRotatedSession(
            token = session.token,
            refreshToken = session.refreshToken,
            fileToken = session.fileToken,
            xmppPassword = session.xmppPassword
        )
        deadRefreshToken = null
        return session
    }

    private suspend fun defaultRotate(refreshToken: String): RefreshedSession {
        val api = ApiClient.createService<AuthAPI>(ChatStore.getEffectiveBaseUrl())
        val response = api.loginRefresh(refreshToken)
        if (response.isSuccessful) {
            val body = response.body() ?: throw IllegalStateException("Refresh response has no body")
            val newRefresh = body.refreshToken
            if (body.token.isBlank() || newRefresh.isNullOrBlank()) {
                throw IllegalStateException("Refresh response did not contain both tokens")
            }
            return RefreshedSession(body.token, newRefresh, body.fileToken, body.xmppPassword)
        }
        val code = parseRefreshErrorCode(response.errorBody()?.string(), response.headers()["x-error-code"])
        throw RefreshHttpException(code, "Token refresh failed: ${response.code()} ${response.message()}")
    }
}
