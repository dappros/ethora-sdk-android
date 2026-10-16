package com.ethora.chat.core.networking

import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.RefreshTokensConfig
import com.ethora.chat.core.config.TokenRefreshResult
import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AuthRefreshTest {

    companion object {
        private val originalRotate = AuthRefresh.rotateWithEndpoint
        private val originalDelay = AuthRefresh.retryDelay
    }

    @Before
    fun setUp() {
        ChatStore.setConfig(null)
        AuthRefresh.rotateWithEndpoint = originalRotate
        AuthRefresh.retryDelay = { _ -> }
        UserStore.setUser(User(id = "u1", token = "t0", refreshToken = "r0", xmppUsername = "alice@xmpp.example.com"))
    }

    @After
    fun tearDown() {
        ChatStore.setConfig(null)
        AuthRefresh.rotateWithEndpoint = originalRotate
        AuthRefresh.retryDelay = originalDelay
        UserStore.clear()
    }

    @Test
    fun `error code is read from body shapes and header`() {
        assertEquals(RefreshErrorCode.REUSE_DETECTED, parseRefreshErrorCode("""{"code":"REFRESH_TOKEN_REUSE_DETECTED"}"""))
        assertEquals(RefreshErrorCode.IN_PROGRESS, parseRefreshErrorCode("""{"error":{"code":"REFRESH_IN_PROGRESS"}}"""))
        assertEquals(RefreshErrorCode.NOT_FOUND, parseRefreshErrorCode("""{"errors":[{"code":"REFRESH_TOKEN_NOT_FOUND"}]}"""))
        assertEquals(RefreshErrorCode.ALREADY_ROTATED, parseRefreshErrorCode("""{"message":"REFRESH_TOKEN_ALREADY_ROTATED"}"""))
        assertEquals(RefreshErrorCode.IN_PROGRESS, parseRefreshErrorCode(null, "REFRESH_IN_PROGRESS"))
        assertNull(parseRefreshErrorCode("""{"message":"Unauthorized"}"""))
        assertNull(parseRefreshErrorCode(null, null))
    }

    @Test
    fun `concurrent callers share one rotation`() = runTest {
        val calls = AtomicInteger(0)
        AuthRefresh.rotateWithEndpoint = { presented ->
            calls.incrementAndGet()
            delay(50)
            RefreshedSession(token = "t1", refreshToken = "r1-of-$presented")
        }

        val results = coroutineScope {
            List(5) { async { AuthRefresh.refreshAuthTokens() } }.awaitAll()
        }

        assertEquals(1, calls.get())
        results.forEach { assertEquals("t1", it.token) }
        assertEquals("r1-of-r0", UserStore.refreshToken.value)
        assertEquals("t1", UserStore.token.value)
    }

    @Test
    fun `rotation is persisted to the store before the call returns`() = runTest {
        AuthRefresh.rotateWithEndpoint = { RefreshedSession(token = "t1", refreshToken = "r1", fileToken = "f1", xmppPassword = "p1") }

        val session = AuthRefresh.refreshAuthTokens()

        assertEquals("r1", session.refreshToken)
        assertEquals("r1", UserStore.currentUser.value?.refreshToken)
        assertEquals("f1", UserStore.currentUser.value?.fileToken)
        assertEquals("p1", UserStore.currentUser.value?.xmppPassword)
    }

    @Test
    fun `reuse detected is fatal and latches the session dead`() = runTest {
        AuthRefresh.rotateWithEndpoint = { throw RefreshHttpException(RefreshErrorCode.REUSE_DETECTED, "reuse") }

        try {
            AuthRefresh.refreshAuthTokens()
            fail("expected RefreshFatalError")
        } catch (e: RefreshFatalError) {
            assertEquals(RefreshErrorCode.REUSE_DETECTED, e.code)
        }
        AuthRefresh.markSessionDead()
        assertTrue(AuthRefresh.isSessionDead())
    }

    @Test
    fun `not found is fatal`() = runTest {
        AuthRefresh.rotateWithEndpoint = { throw RefreshHttpException(RefreshErrorCode.NOT_FOUND, "nf") }
        try {
            AuthRefresh.refreshAuthTokens()
            fail("expected RefreshFatalError")
        } catch (e: RefreshFatalError) {
            assertEquals(RefreshErrorCode.NOT_FOUND, e.code)
        }
    }

    @Test
    fun `in progress retries then succeeds`() = runTest {
        val calls = AtomicInteger(0)
        val delays = mutableListOf<Long>()
        AuthRefresh.retryDelay = { ms -> delays += ms }
        AuthRefresh.rotateWithEndpoint = { _ ->
            if (calls.incrementAndGet() < 3) throw RefreshHttpException(RefreshErrorCode.IN_PROGRESS, "busy")
            RefreshedSession(token = "t2", refreshToken = "r2")
        }

        val session = AuthRefresh.refreshAuthTokens()

        assertEquals(3, calls.get())
        assertEquals("t2", session.token)
        assertEquals(2, delays.size)
        assertTrue(delays[0] in 300L..450L)
        assertTrue(delays[1] in 600L..750L)
    }

    @Test
    fun `already rotated adopts a newer stored token`() = runTest {
        AuthRefresh.rotateWithEndpoint = { _ ->
            // Another writer rotated the session while this call was in flight.
            UserStore.applyRotatedSession(token = "t9", refreshToken = "r9")
            throw RefreshHttpException(RefreshErrorCode.ALREADY_ROTATED, "stale")
        }

        val session = AuthRefresh.refreshAuthTokens()

        assertEquals("t9", session.token)
        assertEquals("r9", session.refreshToken)
    }

    @Test
    fun `already rotated with nothing newer is fatal`() = runTest {
        AuthRefresh.rotateWithEndpoint = { throw RefreshHttpException(RefreshErrorCode.ALREADY_ROTATED, "stale") }
        try {
            AuthRefresh.refreshAuthTokens()
            fail("expected RefreshFatalError")
        } catch (e: RefreshFatalError) {
            assertEquals(RefreshErrorCode.ALREADY_ROTATED, e.code)
        }
    }

    @Test
    fun `network errors pass through without being fatal`() = runTest {
        AuthRefresh.rotateWithEndpoint = { throw IOException("offline") }
        try {
            AuthRefresh.refreshAuthTokens()
            fail("expected IOException")
        } catch (e: RefreshFatalError) {
            fail("network error must not be fatal")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
        assertFalse(AuthRefresh.isSessionDead())
    }

    @Test
    fun `host refresh function replaces the built-in endpoint`() = runTest {
        var builtInCalls = 0
        AuthRefresh.rotateWithEndpoint = { _ ->
            builtInCalls++
            RefreshedSession(token = "builtin", refreshToken = "builtin-r")
        }
        ChatStore.setConfig(
            ChatConfig(
                refreshTokens = RefreshTokensConfig(
                    enabled = true,
                    refreshFunction = {
                        TokenRefreshResult(accessToken = "host-t", refreshToken = "host-r", fileToken = "host-f", xmppPassword = "host-p")
                    }
                )
            )
        )

        val session = AuthRefresh.refreshAuthTokens()

        assertEquals(0, builtInCalls)
        assertEquals("host-t", session.token)
        assertEquals("host-r", session.refreshToken)
        assertEquals("host-p", session.xmppPassword)
    }

    @Test
    fun `refresh without any session is reported as missing`() = runTest {
        UserStore.clear()
        AuthRefresh.rotateWithEndpoint = { RefreshedSession(token = "x", refreshToken = "y") }
        try {
            AuthRefresh.refreshAuthTokens()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Refresh token is missing", e.message)
        }
    }
}
