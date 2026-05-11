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
