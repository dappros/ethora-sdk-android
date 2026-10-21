package com.ethora.chat.core.call.p2p

/**
 * ICE restart policy for a p2p call (web `calls/p2p/icePolicy.ts`), kept apart from the
 * peer connection so it can be reasoned about and tested on its own. The controller feeds
 * it the connection state and runs the timers; the policy only decides.
 *
 *  - `disconnected` is often transient (a Wi-Fi blip heals by itself), so the first
 *    reaction is to wait a grace period and restart only if it has not recovered;
 *  - `failed` is terminal for that ICE session, so restart straight away;
 *  - one restart per outage, and a small cap per call so a flapping network ends in a
 *    clean failure instead of an endless loop;
 *  - a restart that does not bring the connection back within a deadline gives up.
 */
enum class P2PConnState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

sealed interface IceAction {
    data object None : IceAction

    /** Re-check with [IceRestartPolicy.onGraceElapsed] after [ms]. */
    data class Wait(val ms: Long) : IceAction

    /** Restart; call [IceRestartPolicy.onRestartDeadline] after [deadlineMs]. */
    data class Restart(val deadlineMs: Long) : IceAction

    data object Recovered : IceAction

    data class GiveUp(val reason: String) : IceAction
}

class IceRestartPolicy(
    private val graceMs: Long = 3_000,
    private val restartDeadlineMs: Long = 15_000,
    private val maxRestartsPerCall: Int = 3
) {
    var restartCount: Int = 0
        private set
    private var attemptedThisOutage = false
    private var inOutage = false

    private fun tryRestart(): IceAction {
        if (attemptedThisOutage) return IceAction.GiveUp("restart-did-not-recover")
        if (restartCount >= maxRestartsPerCall) return IceAction.GiveUp("too-many-restarts")
        attemptedThisOutage = true
        restartCount += 1
        return IceAction.Restart(restartDeadlineMs)
    }

    fun onState(state: P2PConnState): IceAction = when (state) {
        P2PConnState.CONNECTED -> {
            val wasOutage = inOutage
            inOutage = false
            attemptedThisOutage = false
            if (wasOutage) IceAction.Recovered else IceAction.None
        }
        P2PConnState.DISCONNECTED -> {
            inOutage = true
            if (attemptedThisOutage) IceAction.None else IceAction.Wait(graceMs)
        }
        P2PConnState.FAILED -> {
            inOutage = true
            tryRestart()
        }
        else -> IceAction.None
    }

    /** The grace period after `disconnected` ran out without recovery. */
    fun onGraceElapsed(): IceAction = if (!inOutage) IceAction.None else tryRestart()

    /** The restart deadline ran out; [stillDown] is the state at that moment. */
    fun onRestartDeadline(stillDown: Boolean): IceAction =
        if (stillDown) IceAction.GiveUp("restart-timeout") else IceAction.None
}
