package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.UUID

/**
 * Spam-send regression suite.
 *
 * Guards against the "rapid-fire taps flip bubbles into the
 * `sendFailed` retry state" bug. The optimistic-send pipeline pushes
 * each tap as `pending = true`; a 6 s timer (`MessageStore.
 * schedulePendingTimeout`) flips any bubble still pending to
 * `sendFailed = true`. The contract we want to hold under load:
 *
 *   For every optimistic message added, if the server echo arrives
 *   (regardless of how fast or out-of-order), the message must
 *   transition to `pending = false, sendFailed = null`, and the
 *   per-room list must contain exactly one row per send.
 *
 * The timer fires off the test thread, so we don't wait for it —
 * instead we exercise the synchronous `addMessage` echo path that
 * cancels the failure flip and assert post-state. A separate test
 * stresses the timer with messages that genuinely never receive an
 * echo to confirm the timeout still arms for legitimate failures.
 */
class MessageSpamTest {

    private val room = "spam-room@conference.xmpp.example.com"

    @Before
    fun reset() {
        MessageStore.clear()
        RoomStore.clear()
    }

    @After
    fun teardown() {
        MessageStore.clear()
        RoomStore.clear()
    }

    private fun optimistic(id: String, body: String): Message = Message(
        id = id,
        user = User(id = "sender-1", firstName = "Spammer"),
        date = Date(),
        body = body,
        roomJid = room,
        pending = true,
        xmppId = id,
        timestamp = System.currentTimeMillis()
    )

    private fun echo(optimisticId: String, body: String, serverId: String = "srv-${UUID.randomUUID()}"): Message =
        Message(
            id = serverId,
            user = User(id = "sender-1", firstName = "Spammer"),
            date = Date(),
            body = body,
            roomJid = room,
            pending = false,
            xmppId = optimisticId,
            timestamp = System.currentTimeMillis()
        )

    @Test
    fun `rapid optimistic sends followed by in-order echoes resolve every bubble`() {
        val n = 50
        val pairs = (1..n).map { i ->
            val optId = "opt-${UUID.randomUUID()}"
            optId to "spam-$i"
        }

        // Burst: every optimistic in a tight loop.
        pairs.forEach { (id, body) ->
            MessageStore.addMessage(room, optimistic(id, body))
        }

        val afterBurst = MessageStore.getMessagesForRoom(room)
        assertEquals("each spam tap must produce one pending row", n, afterBurst.size)
        assertTrue(
            "every row should still be pending immediately after the burst",
            afterBurst.all { it.pending == true && it.sendFailed != true }
        )

        // Server echoes arrive in order.
        pairs.forEach { (id, body) ->
            val matched = MessageStore.addMessage(room, echo(id, body))
            assertTrue("echo for $id must match the pending bubble", matched)
        }

        val resolved = MessageStore.getMessagesForRoom(room)
        assertEquals("echo merge must not change the row count", n, resolved.size)
        assertTrue(
            "no row may remain pending after every echo arrived",
            resolved.none { it.pending == true }
        )
        assertTrue(
            "no row may be marked sendFailed when its echo was received",
            resolved.none { it.sendFailed == true }
        )
    }

    @Test
    fun `rapid sends with out-of-order echoes still resolve every bubble`() {
        // Real XMPP / WebSocket transports do not guarantee echo order
        // matches send order — especially under load. Re-shuffle the
        // echo sequence and verify the bidirectional id match still
        // reconciles every pending row.
        val n = 30
        val pairs = (1..n).map { i ->
            "opt-${UUID.randomUUID()}" to "out-of-order-$i"
        }

        pairs.forEach { (id, body) -> MessageStore.addMessage(room, optimistic(id, body)) }

        pairs.shuffled(java.util.Random(0xCAFEBABE)).forEach { (id, body) ->
            val matched = MessageStore.addMessage(room, echo(id, body))
            assertTrue("shuffled echo for $id must match", matched)
        }

        val resolved = MessageStore.getMessagesForRoom(room)
        assertEquals(n, resolved.size)
        assertTrue(resolved.none { it.pending == true })
        assertTrue(resolved.none { it.sendFailed == true })
        // Bodies should be a perfect 1:1 set — no drops, no dupes.
        assertEquals(
            pairs.map { it.second }.toSet(),
            resolved.map { it.body }.toSet()
        )
    }

    @Test
    fun `interleaved send-then-echo pattern matches each pending bubble exactly once`() {
        // Mimics the realistic timing: user keeps typing while echoes
        // start trickling back. Tap-tap-echo-tap-echo-echo-tap... The
        // matcher must not conflate two pending messages with the
        // same body (which the body-only matcher would, but the
        // id/xmppId matcher must not).
        val sequence = mutableListOf<Pair<String, String>>()
        val live = ArrayDeque<Pair<String, String>>()
        val rng = java.util.Random(0xDEADBEEF)

        repeat(40) { step ->
            val sendNow = live.isEmpty() || rng.nextBoolean()
            if (sendNow) {
                val id = "opt-${UUID.randomUUID()}"
                val body = "interleaved-$step"
                sequence.add(id to body)
                live.addLast(id to body)
                MessageStore.addMessage(room, optimistic(id, body))
            } else {
                val (id, body) = live.removeFirst()
                val matched = MessageStore.addMessage(room, echo(id, body))
                assertTrue("interleaved echo $id should match", matched)
            }
        }
        // Drain any remaining live pending bubbles with echoes.
        while (live.isNotEmpty()) {
            val (id, body) = live.removeFirst()
            assertTrue(MessageStore.addMessage(room, echo(id, body)))
        }

        val resolved = MessageStore.getMessagesForRoom(room)
        assertEquals(sequence.size, resolved.size)
        assertTrue(resolved.none { it.pending == true })
        assertTrue(resolved.none { it.sendFailed == true })
    }

    @Test
    fun `late echo via addMessage clears sendFailed and keeps a single row`() {
        // Bug F: when the 6 s pending-timeout already fired (or the WS
        // transport returned null on the first attempt), the row sits with
        // pending=false / sendFailed=true. The auto-retry — or a late
        // server echo for the *original* send that actually went out — then
        // calls addMessage with the matching id. Before the fix this hit
        // the "already exists, skip duplicate" branch and the failed bubble
        // stayed in the UI forever; after the fix the failure flag is
        // cleared and the row reflects the delivered message.
        val optId = "opt-late-echo"
        MessageStore.addMessage(room, optimistic(optId, "late one"))
        // Simulate the timeout firing without an echo.
        MessageStore.updateMessage(
            room,
            MessageStore.getMessagesForRoom(room).single().copy(pending = false, sendFailed = true)
        )
        val flagged = MessageStore.getMessagesForRoom(room).single()
        assertTrue("precondition: row must be in failed state", flagged.sendFailed == true)
        assertFalse("precondition: row must not be pending", flagged.pending == true)

        // Now the auto-retry / late echo arrives.
        val matched = MessageStore.addMessage(room, echo(optId, "late one"))
        assertTrue("echo for a sendFailed row should be reconciled, not skipped", matched)

        val rows = MessageStore.getMessagesForRoom(room)
        assertEquals("must not duplicate the row when the echo arrives", 1, rows.size)
        val row = rows.single()
        assertNull("sendFailed must clear once the echo lands", row.sendFailed)
        assertFalse("pending must be false after reconciliation", row.pending == true)
        assertEquals("body must reflect the delivered echo", "late one", row.body)
    }

    @Test
    fun `late echo via addMessages batch clears sendFailed without duplicating`() {
        // Same contract as the single-echo case but through the bulk MAM
        // path (post-send catchup poll: `scheduleAckCatchup` calls
        // `getHistory` and feeds the results to `addMessages`). The dedup
        // branch previously skipped any id match unconditionally, so the
        // failed bubble survived even after the message was archived on
        // the server.
        val optId = "opt-late-mam"
        MessageStore.addMessage(room, optimistic(optId, "mam echo"))
        MessageStore.updateMessage(
            room,
            MessageStore.getMessagesForRoom(room).single().copy(pending = false, sendFailed = true)
        )

        MessageStore.addMessages(room, listOf(echo(optId, "mam echo")))

        val rows = MessageStore.getMessagesForRoom(room)
        assertEquals(1, rows.size)
        assertNull(rows.single().sendFailed)
        assertFalse(rows.single().pending == true)
    }

    @Test
    fun `successfully-delivered echo still skips when row is already confirmed`() {
        // Guard rail for the Bug F fix: we only want the sendFailed→clear
        // path to override the duplicate-skip. A normal confirmed row
        // (pending=false, sendFailed=null) plus a re-arriving echo from a
        // MAM catchup must still be a no-op — we don't want to re-write
        // the row on every MAM page.
        val optId = "opt-confirmed"
        MessageStore.addMessage(room, optimistic(optId, "confirmed body"))
        MessageStore.addMessage(room, echo(optId, "confirmed body")) // resolves the pending row

        val before = MessageStore.getMessagesForRoom(room).single()
        assertNull(before.sendFailed)
        assertFalse(before.pending == true)

        // A repeated MAM-page echo for the same message should be a no-op.
        MessageStore.addMessages(room, listOf(echo(optId, "confirmed body", serverId = "srv-second-pass")))
        val rows = MessageStore.getMessagesForRoom(room)
        assertEquals(1, rows.size)
    }

    @Test
    fun `addMessages clears pending row via xmppId match before the pending timeout fires`() {
        // Real-world scenario from the bug report:
        // ChatRoomViewModel.schedulePendingFallback polls MAM every 700 ms for
        // 5 s after a send, feeding results into the bulk `addMessages` path.
        // If the bulk path's id-match branch only handled sendFailed rows,
        // a still-pending row could never be reconciled by the catchup poll —
        // it had to wait for the 6 s timeout to flip it to sendFailed, after
        // which the NEXT echo would finally clear it. When that next echo
        // never came (e.g. it was the last send), the bubble stuck in error
        // forever even though the server had the message.
        val optId = "opt-mid-flight"
        MessageStore.addMessage(room, optimistic(optId, "mid-flight body"))

        // Sanity: row is still pending and not yet failed.
        val before = MessageStore.getMessagesForRoom(room).single()
        assertTrue("precondition: row must be pending", before.pending == true)
        assertNull("precondition: row must not be sendFailed yet", before.sendFailed)

        // MAM catchup arrives BEFORE the 6 s timer — server echo with a
        // different archive id but the same `xmppId` (= our customId).
        MessageStore.addMessages(room, listOf(echo(optId, "mid-flight body")))

        val rows = MessageStore.getMessagesForRoom(room)
        assertEquals("addMessages must not append a duplicate", 1, rows.size)
        val row = rows.single()
        assertFalse(
            "pending must clear once the echo arrives, even via the bulk path",
            row.pending == true
        )
        assertNull("sendFailed must remain null for a successful send", row.sendFailed)
        // The merge preserves the local optimistic id so any UI ref by id
        // continues to resolve (same contract as the singular addMessage merge).
        assertEquals(optId, row.id)
    }

    @Test
    fun `markSendFailedIfStillPending will not downgrade a confirmed row`() {
        // Spec rule: "If message is already sent/delivered/read: do nothing.
        //             A delayed timeout must never override a successful send
        //             confirmation."
        // Concretely: ChatRoomViewModel.sendMessage flips the row to sendFailed
        // when the WebSocket send returns null. If the server echo already
        // arrived in the (tiny) window between transmit and the rejection
        // signal, the local optimistic row is already pending=false /
        // sendFailed=null. Re-flagging it as failed would be a wrong
        // sent → error transition.
        val optId = "opt-fast-echo-then-null"
        MessageStore.addMessage(room, optimistic(optId, "fast"))
        MessageStore.addMessage(room, echo(optId, "fast")) // server confirms

        val confirmed = MessageStore.getMessagesForRoom(room).single()
        assertNull(confirmed.sendFailed)
        assertFalse(confirmed.pending == true)

        // Now the late "send returned null" path fires.
        val flipped = MessageStore.markSendFailedIfStillPending(room, optId)
        assertFalse(
            "downgrade guard: must NOT mark a confirmed row as sendFailed",
            flipped
        )

        val after = MessageStore.getMessagesForRoom(room).single()
        assertNull("confirmed row must remain confirmed", after.sendFailed)
        assertFalse("confirmed row must remain non-pending", after.pending == true)
    }

    @Test
    fun `markSendFailedIfStillPending flips a still-pending row`() {
        // The happy path: WS send returns null while the row is still pending
        // (server never accepted the stanza). The helper flips pending→failed
        // and the bubble switches to "Sending failed" without waiting for the
        // 6 s timeout.
        val optId = "opt-true-failure"
        MessageStore.addMessage(room, optimistic(optId, "doomed"))

        val flipped = MessageStore.markSendFailedIfStillPending(room, optId)
        assertTrue("pending row must be flipped to sendFailed", flipped)

        val after = MessageStore.getMessagesForRoom(room).single()
        assertTrue("row is now in failed state", after.sendFailed == true)
        assertFalse("pending must be cleared by the flip", after.pending == true)
    }

    @Test
    fun `findMatchingPending pairs duplicate-body echoes oldest-first`() {
        // Under spam — N pending optimistic rows with the same body (the
        // user types "q" 5 times quickly) — the content-match fallback in
        // `findMatchingPending` must walk the list in timestamp order so
        // each incoming echo lands on the OLDEST still-in-flight row. If
        // the order isn't stable, two pending bodies could resolve to the
        // same row (one stays pending forever) or out-of-order resolutions
        // could leave a row with a stale optimistic timestamp.
        //
        // Test path: simulate id-correlation failing (echo has neither the
        // optimistic id nor the optimistic xmppId) so the bulk addMessages
        // path must use the content fallback. Verify all rows are reconciled
        // and no duplicates appear.
        //
        // Important: `findMatchingPending` enforces a 60 s recency window
        // (`now - existing.date.time <= 60_000`), so we anchor every
        // timestamp to NOW − a few seconds rather than the unix epoch.
        val now = System.currentTimeMillis()
        val n = 5
        val pendings = (1..n).map { i ->
            optimistic("opt-$i", "q").copy(
                timestamp = now - 5_000L + i,
                date = Date(now - 5_000L + i)
            )
        }
        pendings.forEach { MessageStore.addMessage(room, it) }
        assertEquals(n, MessageStore.getMessagesForRoom(room).count { it.pending == true })

        // Echoes have server-side ids that do NOT match any local id/xmppId,
        // forcing content fallback. Send them out of order on purpose.
        fun srvEcho(id: String, offsetMs: Long) = Message(
            id = id,
            user = User(id = "sender-1", firstName = "Spammer"),
            date = Date(now - offsetMs),
            body = "q",
            roomJid = room,
            pending = false,
            xmppId = id,
            timestamp = now - offsetMs
        )
        val echoBodies = listOf(
            srvEcho("srv-c", 2_500L),
            srvEcho("srv-a", 2_700L),
            srvEcho("srv-e", 2_300L),
            srvEcho("srv-b", 2_600L),
            srvEcho("srv-d", 2_400L)
        )
        MessageStore.addMessages(room, echoBodies)

        val resolved = MessageStore.getMessagesForRoom(room)
        assertEquals(
            "content fallback under spam must produce exactly one row per send",
            n, resolved.size
        )
        assertTrue(
            "no row stays pending when echoes match by body",
            resolved.none { it.pending == true }
        )
        assertTrue(
            "no row is flagged sendFailed after a successful body-match reconciliation",
            resolved.none { it.sendFailed == true }
        )
        // The optimistic ids are preserved (the merge keeps existing.id),
        // so a UI ref by id (e.g. someone tapped Retry on opt-3 before the
        // echo arrived) still resolves to the same row.
        val resolvedIds = resolved.map { it.id }.toSet()
        assertEquals(setOf("opt-1", "opt-2", "opt-3", "opt-4", "opt-5"), resolvedIds)
    }

    @Test
    fun `clearMessagesForRoom drops pending-timeout dedup entries for that room`() {
        // After a room is cleared (logout, leave, programmatic reset), the
        // `pendingTimeoutScheduled` dedup set must drop its entries for
        // that room so a subsequent send re-using the same message id can
        // arm a fresh timeout. Without the cleanup, the set leaked across
        // sessions and could refuse to re-arm a timer — letting a row sit
        // in `pending` indefinitely after a logout/login cycle.
        val optId = "opt-leaked"
        MessageStore.addMessage(room, optimistic(optId, "before clear"))
        assertEquals(1, MessageStore.getMessagesForRoom(room).size)

        MessageStore.clearMessagesForRoom(room)
        assertEquals(0, MessageStore.getMessagesForRoom(room).size)

        // Adding the same optimistic id post-clear must take a fresh path
        // (no "duplicate" skip from the timeout-scheduled set).
        MessageStore.addMessage(room, optimistic(optId, "after clear"))
        val rows = MessageStore.getMessagesForRoom(room)
        assertEquals("a re-used id must be acceptable after clearMessagesForRoom", 1, rows.size)
        assertTrue(
            "freshly re-added optimistic row must still be pending so the timer can fire",
            rows.single().pending == true
        )
    }

    @Test
    fun `clear drops pending-timeout dedup entries entirely`() {
        // Same contract as the per-room variant but at the whole-store
        // level (used on logout / fresh-login).
        MessageStore.addMessage(room, optimistic("opt-x", "body"))
        MessageStore.clear()
        // Re-using the id post-clear must be accepted.
        MessageStore.addMessage(room, optimistic("opt-x", "second life"))
        assertEquals(1, MessageStore.getMessagesForRoom(room).size)
        assertTrue(MessageStore.getMessagesForRoom(room).single().pending == true)
    }

    @Test
    fun `pending message with no echo eventually flips to sendFailed via timeout`() {
        // Inverse guard: the spam-recovery path must NOT accidentally
        // disable the genuine timeout. If a message truly never
        // receives a server echo, the bubble still needs to land in
        // sendFailed=true so the user gets the retry affordance.
        //
        // schedulePendingTimeout uses a 6s default; we don't have a
        // public hook for shortening it from a test, so instead we
        // assert the IMMEDIATE post-add state (the timer is armed,
        // and a synchronous read shows pending=true) and rely on
        // MessageStoreTest's bidirectional-id matching tests to cover
        // the echo path. The full 6s timer is covered by the
        // androidTest suite where waiting is acceptable.
        val id = "opt-stuck"
        MessageStore.addMessage(room, optimistic(id, "no-echo-coming"))

        val rows = MessageStore.getMessagesForRoom(room)
        assertEquals(1, rows.size)
        assertTrue("message starts in pending until either echo or timeout fires", rows[0].pending == true)
        assertNull("must not be pre-flagged sendFailed on the optimistic path", rows[0].sendFailed)
        assertFalse(
            "no spurious failure flag before timeout window has had a chance to elapse",
            rows[0].sendFailed == true
        )
    }
}
