package com.ethora.chat.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sender and text of a message a jump asks for, shown when it cannot be placed in the transcript. */
data class PendingJumpPreview(
    val roomJid: String,
    val sender: String,
    val body: String,
    val createdAt: String?
)

/**
 * "Scroll to this message" request, waiting for the room that owns it. Web
 * keeps the same thing in redux (rooms.pendingJump).
 *  - [ids]: every id the caller knows the message by (archive id, client id);
 *  - [createdAt] / [body]: what the message says, for a match when ids fail;
 *  - [at]: unique per request, so a room acts on each request once.
 */
data class PendingJump(
    val roomJid: String,
    val ids: List<String>,
    val createdAt: String?,
    val body: String?,
    val preview: PendingJumpPreview?,
    val at: Long,
    val requestedAtMs: Long
)

object JumpRequestStore {
    /** How long a request may stay pending before it is dropped. */
    const val JUMP_TTL_MS = 60_000L

    private val _pending = MutableStateFlow<PendingJump?>(null)
    val pending: StateFlow<PendingJump?> = _pending.asStateFlow()

    /** Asks [roomJid] to land on the message. Blank and duplicate ids are dropped. */
    fun request(
        roomJid: String,
        ids: List<String?>,
        createdAt: String? = null,
        body: String? = null,
        preview: PendingJumpPreview? = null
    ): PendingJump {
        val cleanIds = ids.mapNotNull { it?.trim()?.takeIf { id -> id.isNotEmpty() } }.distinct()
        val jump = PendingJump(
            roomJid = roomJid,
            ids = cleanIds,
            createdAt = createdAt,
            body = body,
            preview = preview,
            at = System.nanoTime(),
            requestedAtMs = System.currentTimeMillis()
        )
        _pending.value = jump
        return jump
    }

    fun isExpired(jump: PendingJump, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - jump.requestedAtMs > JUMP_TTL_MS

    /** Marks [at] as handled. A newer request is left alone. */
    fun consume(at: Long) {
        if (_pending.value?.at == at) _pending.value = null
    }

    fun clear() {
        _pending.value = null
    }
}
