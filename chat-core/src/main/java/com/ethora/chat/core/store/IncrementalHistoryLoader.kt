package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background catchup fetcher. Ports web's
 * `helpers/updateMessagesTillLast.tsx` one-for-one:
 *   • Sort rooms: active room first, then by last activity score.
 *   • Process in batches (default 2) with a 125 ms stagger per batch slot.
 *   • For each room, do ONE fetch of 20 messages.
 *   • Compute "anchor" = last non-pending, non-delimiter message in the
 *     local cache (not merely the `lastMessageTimestamp` on the Room).
 *   • If anchor is found inside the fetched messages → append only the
 *     messages newer than it (delta merge).
 *   • If anchor is NOT found → the local cache is stale; replace the room
 *     cache with the server's latest page.
 *   • If there is no anchor (empty cache) → just set the fetched page.
 */
object IncrementalHistoryLoader {
    private const val TAG = "IncrementalHistoryLoader"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class RoomAnchor(
        val id: String?,
        val xmppId: String?,
        val timestamp: Long
    )

    /** Last non-pending, non-delimiter message. Matches web's
     * `getAnchorFromRoom`: scans from newest → oldest and picks the first
     * message that was actually delivered. */
    private fun getRoomAnchor(room: Room): RoomAnchor? {
        val cached = MessageStore.getMessagesForRoom(room.jid)
        if (cached.isEmpty()) return null
        val sorted = cached.sortedBy { it.timestamp ?: it.date.time }
        for (i in sorted.indices.reversed()) {
            val m = sorted[i]
            if (m.id == "delimiter-new") continue
            if (m.pending == true) continue
            if (m.body.isBlank() && m.isMediafile != "true") continue
            return RoomAnchor(
                id = m.id,
                xmppId = m.xmppId,
                timestamp = m.timestamp ?: m.date.time
            )
        }
        return null
    }

    /** 4-level fallback match, same priority as web's `anchorMatches`:
     *  stableKey (xmppId || id), then xmppId, then id, then timestamp. */
    private fun anchorMatches(
        anchor: RoomAnchor,
        candidate: com.ethora.chat.core.models.Message
    ): Boolean {
        val candidateStable = (candidate.xmppId ?: candidate.id).trim()
        val anchorStable = (anchor.xmppId ?: anchor.id).orEmpty().trim()
        if (anchorStable.isNotEmpty() && candidateStable.isNotEmpty() && anchorStable == candidateStable) return true

        if (!anchor.xmppId.isNullOrBlank() && !candidate.xmppId.isNullOrBlank() &&
            anchor.xmppId == candidate.xmppId) return true

        if (!anchor.id.isNullOrBlank() && anchor.id == candidate.id) return true

        val ts = candidate.timestamp ?: candidate.date.time
        return ts > 0 && anchor.timestamp > 0 && ts == anchor.timestamp
    }

    private fun roomActivityScore(room: Room): Long =
        room.lastMessageTimestamp ?: 0L

    suspend fun updateMessagesTillLast(
        xmppClient: XMPPClient?,
        batchSize: Int = 2,
        messagesPerFetch: Int = 20
    ) {
        if (xmppClient == null) {
            Log.w(TAG, "XMPP client is null, skipping catchup")
            return
        }
        val rooms = RoomStore.rooms.value
        if (rooms.isEmpty()) return

        // Active room first, then by most recent activity, then alphabetical.
        val activeJid = RoomStore.currentRoom.value?.jid
        val sorted = rooms.sortedWith(
            compareByDescending<Room> { it.jid == activeJid }
                .thenByDescending { roomActivityScore(it) }
                .thenBy { it.jid }
        )

        val normalizedBatchSize = maxOf(1, batchSize)
        var processedIndex = 0
        while (processedIndex < sorted.size) {
            val currentBatch = sorted.subList(
                processedIndex,
                minOf(processedIndex + normalizedBatchSize, sorted.size)
            )

            currentBatch.mapIndexed { index, room ->
                scope.launch {
                    try {
                        if (index > 0) delay(125)

                        // Anchor snapshot must be taken from the latest store
                        // state, not a pre-batch snapshot — messages can arrive
                        // via real-time between the sort and this point.
                        val anchor = getRoomAnchor(room)

                        val latest = try {
                            xmppClient.getHistory(
                                room.jid,
                                max = messagesPerFetch,
                                beforeMessageId = null
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "getHistory failed for ${room.jid}", e)
                            emptyList()
                        }
                        if (latest.isEmpty()) return@launch

                        if (anchor == null) {
                            // Empty cache — seed with what the server has.
                            MessageStore.setMessagesForRoom(room.jid, latest)
                            return@launch
                        }

                        val anchorFound = latest.any { anchorMatches(anchor, it) }
                        if (anchorFound) {
                            // Delta merge: only messages strictly newer than the anchor.
                            val delta = latest.filter { msg ->
                                val ts = msg.timestamp ?: msg.date.time
                                ts > anchor.timestamp
                            }
                            if (delta.isNotEmpty()) {
                                MessageStore.addMessages(room.jid, delta)
                            }
                        } else {
                            // Anchor miss — local cache diverged (e.g. a long
                            // offline window). Trust the server's latest page.
                            Log.w(TAG, "Anchor miss for ${room.jid}; replacing cache")
                            MessageStore.setMessagesForRoom(room.jid, latest)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing room ${room.jid}", e)
                    }
                }
            }.forEach { it.join() }

            processedIndex += normalizedBatchSize
        }
    }
}
