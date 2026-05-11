package com.ethora.chat.core.service

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Unit tests for the [LogoutService] cross-store cleanup contract.
 *
 * `LogoutService.performLogout` launches into its own coroutine scope
 * (`scope = Dispatchers.Main + SupervisorJob()`), so `runTest`'s scope
 * does NOT wait on it automatically. The tests synchronise on the
 * documented completion signal — `onLogoutCallback` — via a
 * `CompletableDeferred`. The test body suspends on `done.await()` and
 * resumes once the logout flow has invoked the callback, at which point
 * every cross-store side effect has been applied.
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher())` is installed so the
 * `scope.launch` runs eagerly under the test. IO-dispatched work
 * (persistence saves, `clearAllMessages`) runs on the real IO pool but
 * every IO path is null-guarded — persistence managers and the
 * message cache are never initialised in the JVM-test environment, so
 * each IO step is a no-op that completes well before the callback
 * fires.
 *
 * The XMPP client is left null. Exercising `client.disconnect()`
 * requires a final-class mock of `XMPPClient` plus coordination with
 * the real IO dispatcher — more surface than value for a unit test.
 * Leaving the client null exercises the documented "skipping
 * disconnect" branch (see `LogoutService.kt:112`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutServiceTest {

    @Before
    fun installTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        UserStore.clear()
        RoomStore.clear()
        MessageStore.clear()
        LogoutService.setXMPPClient(null)
        LogoutService.setOnLogoutCallback(null)
    }

    @After
    fun tearDown() {
        UserStore.clear()
        RoomStore.clear()
        MessageStore.clear()
        LogoutService.setXMPPClient(null)
        LogoutService.setOnLogoutCallback(null)
        Dispatchers.resetMain()
    }

    private fun makeRoom(id: String) = Room(
        id = id,
        jid = "$id@conference.xmpp.example.com",
        name = "room-$id",
        title = "Room $id",
        type = RoomType.GROUP,
    )

    private fun makeMessage(id: String, roomJid: String) = Message(
        id = id,
        user = User(id = "u-1", firstName = "Alice"),
        date = Date(),
        body = "msg $id",
        roomJid = roomJid,
    )

    /**
     * Run [block] which invokes `performLogout`, then suspend until the
     * registered callback fires (= the logout flow has actually
     * completed). A 5-second guard prevents the test from hanging if
     * the callback never fires.
     *
     * The `withTimeout` is wrapped in `Dispatchers.Default.limitedParallelism(1)`
     * because under `runTest` `withTimeout` uses *virtual* time by default,
     * which advances independently of the real-thread IO dispatch work
     * happening inside `performLogout`. The wrap switches the timeout to
     * real time, matching what the IO threads experience.
     */
    private suspend fun runLogoutAndAwait(block: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        LogoutService.setOnLogoutCallback {
            // Don't complete twice — the re-entry test re-uses the same
            // callback registration across two logout invocations.
            if (!done.isCompleted) done.complete(Unit)
        }
        block()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { done.await() }
        }
    }

    @Test
    fun `performLogout clears UserStore, RoomStore, and MessageStore`() = runTest {
        // Populate every store the logout flow promises to clean up.
        val room = makeRoom("a")
        UserStore.setUser(User(id = "u-1", firstName = "Alice"))
        RoomStore.setRooms(listOf(room))
        MessageStore.setMessagesForRoom(room.jid, listOf(makeMessage("m-1", room.jid)))

        // Sanity: stores actually have data before we test the clear.
        assertEquals("u-1", UserStore.currentUser.value?.id)
        assertEquals(1, RoomStore.rooms.value.size)
        assertEquals(1, MessageStore.getMessagesForRoom(room.jid).size)

        runLogoutAndAwait { LogoutService.performLogout() }

        assertNull("UserStore current user must clear on logout", UserStore.currentUser.value)
        assertEquals("RoomStore rooms must clear on logout", 0, RoomStore.rooms.value.size)
        assertTrue(
            "MessageStore must clear messages for every room on logout",
            MessageStore.getMessagesForRoom(room.jid).isEmpty()
        )
    }

    @Test
    fun `performLogout invokes the registered logout callback`() = runTest {
        // The "host app's logout completion signal" contract. Hosts wire
        // app-shell navigation onto this callback — if it stops firing,
        // host apps look like they hang on logout.
        var fired = false
        val done = CompletableDeferred<Unit>()
        LogoutService.setOnLogoutCallback {
            fired = true
            if (!done.isCompleted) done.complete(Unit)
        }

        LogoutService.performLogout()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { done.await() }
        }

        assertTrue("onLogoutCallback must fire after the logout flow completes", fired)
    }

    @Test
    fun `performLogout completes cleanly on an already-empty session`() = runTest {
        // Re-entry guard: calling logout twice in a row (or once on a
        // session that already has no user) must not throw and must
        // still invoke the callback. Real failure mode is a UI race
        // where logout fires twice — second call must be a clean no-op,
        // not a crash.
        runLogoutAndAwait { LogoutService.performLogout() }

        // Second call: stores are already empty. Should still complete
        // and invoke the callback.
        runLogoutAndAwait { LogoutService.performLogout() }

        assertNull(UserStore.currentUser.value)
        assertEquals(0, RoomStore.rooms.value.size)
    }
}
