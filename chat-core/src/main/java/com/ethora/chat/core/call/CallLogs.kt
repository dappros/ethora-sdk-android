package com.ethora.chat.core.call

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import java.util.Date

/**
 * Call-log system rows in the chat, Telegram/WhatsApp style:
 *  - "Outgoing call · 12 sec", "Incoming call · 1 min", "No answer", "Missed call".
 *
 * Mirrors web `helpers/callLogMessage.ts`. Log rows are identified by the
 * deterministic id `calllog-<callId>`, so the local entry written when we hang
 * up and the server call-state broadcast collapse into one row.
 */
object CallLogs {
    const val ID_PREFIX = "calllog-"

    /** True for a row built by this class (live, archived or local). */
    fun isCallLog(message: Message): Boolean = message.id.startsWith(ID_PREFIX)

    fun body(direction: CallDirection, durationMs: Long): String {
        val safe = durationMs.coerceAtLeast(0L)
        val isOutgoing = direction == CallDirection.OUTGOING
        return if (safe > 0) {
            "${if (isOutgoing) "Outgoing" else "Incoming"} call · ${formatDuration(safe)}"
        } else {
            if (isOutgoing) "No answer" else "Missed call"
        }
    }

    /** "12 sec", "1 min", "1 min 5 sec" (rounded to whole seconds). */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = kotlin.math.round(durationMs.coerceAtLeast(0L) / 1000.0).toLong()
        if (totalSeconds < 60) return "$totalSeconds sec"
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (seconds == 0L) "$minutes min" else "$minutes min $seconds sec"
    }

    /** Log row written on this device when a call ends locally. */
    fun buildLocal(
        callId: String,
        direction: CallDirection,
        durationMs: Long,
        roomJid: String,
        selfXmppUsername: String,
        now: Long = System.currentTimeMillis()
    ): Message = build(
        callId = callId,
        direction = direction,
        durationMs = durationMs,
        roomJid = roomJid,
        senderXmppUsername = selfXmppUsername,
        now = now
    )

    /**
     * Log row for a server call-state broadcast or archive entry. The direction
     * comes from the caller: when the caller is us it was an outgoing call.
     */
    fun buildFromServer(
        callId: String?,
        callerXmppUsername: String?,
        durationMs: Long?,
        roomJid: String,
        selfXmppUsername: String,
        now: Long = System.currentTimeMillis()
    ): Message {
        val callerLocal = callerXmppUsername.orEmpty().substringBefore("@").trim()
        val selfLocal = selfXmppUsername.substringBefore("@").trim()
        val direction = if (callerLocal.isNotEmpty() && callerLocal == selfLocal) {
            CallDirection.OUTGOING
        } else {
            CallDirection.INCOMING
        }
        return build(
            callId = callId.orEmpty(),
            direction = direction,
            durationMs = durationMs ?: 0L,
            roomJid = roomJid,
            senderXmppUsername = selfXmppUsername,
            now = now
        )
    }

    private fun build(
        callId: String,
        direction: CallDirection,
        durationMs: Long,
        roomJid: String,
        senderXmppUsername: String,
        now: Long
    ): Message {
        val id = if (callId.isNotBlank()) "$ID_PREFIX$callId" else "$ID_PREFIX$now"
        return Message(
            id = id,
            user = User(id = senderXmppUsername.substringBefore("@")),
            date = Date(now),
            body = body(direction, durationMs),
            roomJid = roomJid,
            timestamp = now,
            isSystemMessage = "true",
            xmppId = id
        )
    }
}
