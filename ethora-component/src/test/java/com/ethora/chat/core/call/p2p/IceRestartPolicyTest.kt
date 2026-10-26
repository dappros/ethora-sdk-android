package com.ethora.chat.core.call.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class IceRestartPolicyTest {

    @Test
    fun `a normal connect needs no action`() {
        val p = IceRestartPolicy()
        assertEquals(IceAction.None, p.onState(P2PConnState.CONNECTING))
        assertEquals(IceAction.None, p.onState(P2PConnState.CONNECTED))
    }

    @Test
    fun `disconnected waits, and a heal inside the grace period needs no restart`() {
        val p = IceRestartPolicy(graceMs = 3_000)
        p.onState(P2PConnState.CONNECTED)
        assertEquals(IceAction.Wait(3_000), p.onState(P2PConnState.DISCONNECTED))
        assertEquals(IceAction.Recovered, p.onState(P2PConnState.CONNECTED))
        assertEquals(IceAction.None, p.onGraceElapsed())
    }

    @Test
    fun `disconnected that does not heal restarts after the grace period`() {
        val p = IceRestartPolicy(restartDeadlineMs = 15_000)
        p.onState(P2PConnState.DISCONNECTED)
        assertEquals(IceAction.Restart(15_000), p.onGraceElapsed())
        assertEquals(1, p.restartCount)
    }

    @Test
    fun `failed restarts at once, a second failure in the same outage gives up`() {
        val p = IceRestartPolicy()
        assertEquals(IceAction.Restart(15_000), p.onState(P2PConnState.FAILED))
        assertEquals(IceAction.GiveUp("restart-did-not-recover"), p.onState(P2PConnState.FAILED))
    }

    @Test
    fun `restarts per call are capped`() {
        val p = IceRestartPolicy(maxRestartsPerCall = 2)
        repeat(2) {
            assertEquals(IceAction.Restart(15_000), p.onState(P2PConnState.FAILED))
            assertEquals(IceAction.Recovered, p.onState(P2PConnState.CONNECTED))
        }
        assertEquals(IceAction.GiveUp("too-many-restarts"), p.onState(P2PConnState.FAILED))
    }

    @Test
    fun `the restart deadline gives up only while still down`() {
        val p = IceRestartPolicy()
        assertEquals(IceAction.GiveUp("restart-timeout"), p.onRestartDeadline(stillDown = true))
        assertEquals(IceAction.None, p.onRestartDeadline(stillDown = false))
    }
}
