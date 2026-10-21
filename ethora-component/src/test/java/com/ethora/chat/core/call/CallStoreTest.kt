package com.ethora.chat.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** State transitions (port of web callSlice reducers) and call-log formatting. */
class CallStoreTest {

    @Before
    fun reset() {
        CallStore.reset()
    }

    @Test
    fun `outgoing dial moves to requesting and anchors start time`() {
        CallStore.startOutgoingCall("app_r1@conf", "Bob", null, CallKind.AUDIO, "bob", now = 100L)
        val s = CallStore.snapshot()
        assertEquals(CallPhase.REQUESTING, s.phase)
        assertEquals(CallDirection.OUTGOING, s.direction)
        assertEquals(CallKind.AUDIO, s.kind)
        assertEquals("app_r1", s.roomBareName)
        assertEquals("bob", s.peerXmppUsername)
        assertEquals(100L, s.startedAt)
    }

    @Test
    fun `call token for our dial moves caller to connecting`() {
        CallStore.startOutgoingCall("app_r1@conf", "Bob", "app_r1", CallKind.VIDEO, "bob", now = 1L)
        assertTrue(CallStore.setOutgoingCallToken("app_r1@conf", "tok", "c1", now = 2L))
        val s = CallStore.snapshot()
        assertEquals(CallPhase.CONNECTING, s.phase)
        assertEquals("tok", s.token)
        assertEquals("c1", s.callId)
        assertEquals(1L, s.startedAt)
    }

    @Test
    fun `call token for another room is ignored`() {
        CallStore.startOutgoingCall("app_r1@conf", null, null, CallKind.VIDEO, null, now = 1L)
        assertFalse(CallStore.setOutgoingCallToken("other@conf", "tok", null, now = 2L))
        assertEquals(CallPhase.REQUESTING, CallStore.snapshot().phase)
    }

    @Test
    fun `incoming token rings and accept moves to connecting`() {
        CallStore.setIncomingCallToken("app_r2@conf", "Alice", "app_r2", "tok", CallKind.VIDEO, "c2", "alice", now = 5L)
        assertEquals(CallPhase.RINGING_INCOMING, CallStore.snapshot().phase)
        CallStore.acceptIncomingCall(now = 6L)
        assertEquals(CallPhase.CONNECTING, CallStore.snapshot().phase)
    }

    @Test
    fun `accept does nothing for an outgoing call`() {
        CallStore.startOutgoingCall("app_r1@conf", null, null, CallKind.VIDEO, null, now = 1L)
        CallStore.acceptIncomingCall(now = 2L)
        assertEquals(CallPhase.REQUESTING, CallStore.snapshot().phase)
    }

    @Test
    fun `markInCall anchors connect time once`() {
        CallStore.setIncomingCallToken("r@c", null, "r", "t", CallKind.VIDEO, null, null, now = 1L)
        CallStore.markInCall(now = 10L)
        CallStore.markInCall(now = 20L)
        val s = CallStore.snapshot()
        assertEquals(CallPhase.IN_CALL, s.phase)
        assertEquals(10L, s.connectedAt)
    }

    @Test
    fun `error keeps start time and reset returns to idle`() {
        CallStore.startOutgoingCall("r@c", null, null, CallKind.VIDEO, null, now = 3L)
        CallStore.setCallError("No answer - call timed out", now = 4L)
        val err = CallStore.snapshot()
        assertEquals(CallPhase.ERROR, err.phase)
        assertEquals("No answer - call timed out", err.error)
        assertEquals(3L, err.startedAt)
        CallStore.reset()
        assertEquals(CallPhase.IDLE, CallStore.snapshot().phase)
        assertNull(CallStore.snapshot().token)
    }

    @Test
    fun `setCallKind is ignored while idle`() {
        CallStore.setCallKind(CallKind.AUDIO)
        assertEquals(CallKind.VIDEO, CallStore.snapshot().kind)
        CallStore.startOutgoingCall("r@c", null, null, CallKind.VIDEO, null)
        CallStore.setCallKind(CallKind.AUDIO)
        assertEquals(CallKind.AUDIO, CallStore.snapshot().kind)
    }

    @Test
    fun `wire kind parsing defaults to video`() {
        assertEquals(CallKind.AUDIO, CallKind.fromWire("audio"))
        assertEquals(CallKind.AUDIO, CallKind.fromWire(" AUDIO "))
        assertEquals(CallKind.VIDEO, CallKind.fromWire("video"))
        assertEquals(CallKind.VIDEO, CallKind.fromWire(null))
    }

    @Test
    fun `call log labels match web wording`() {
        assertEquals("Outgoing call · 12 sec", CallLogs.body(CallDirection.OUTGOING, 12_000))
        assertEquals("Incoming call · 1 min", CallLogs.body(CallDirection.INCOMING, 60_000))
        assertEquals("Incoming call · 1 min 5 sec", CallLogs.body(CallDirection.INCOMING, 65_000))
        assertEquals("No answer", CallLogs.body(CallDirection.OUTGOING, 0))
        assertEquals("Missed call", CallLogs.body(CallDirection.INCOMING, 0))
    }

    @Test
    fun `local call log uses deterministic id and is recognised`() {
        val msg = CallLogs.buildLocal("c77", CallDirection.OUTGOING, 5_000, "r@c", "me@host", now = 9L)
        assertEquals("calllog-c77", msg.id)
        assertEquals("true", msg.isSystemMessage)
        assertEquals("Outgoing call · 5 sec", msg.body)
        assertTrue(CallLogs.isCallLog(msg))
        assertFalse(CallLogs.isCallLog(msg.copy(id = "m1")))
    }

    @Test
    fun `server log direction comes from the caller`() {
        val mine = CallLogs.buildFromServer("c1", "me", 2_000, "r@c", "me@host")
        assertEquals("Outgoing call · 2 sec", mine.body)
        val theirs = CallLogs.buildFromServer("c1", "alice", 0, "r@c", "me@host")
        assertEquals("Missed call", theirs.body)
    }
}
