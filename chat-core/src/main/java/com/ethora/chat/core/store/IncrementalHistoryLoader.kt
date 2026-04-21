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
 * Incremental history loader - loads messages until finding last cached message timestamp
 * Process in batches of 5 rooms
 * For each room: load messages in chunks of 5 until finding message matching last timestamp
 * or maxFetchAttempts (4) reached
 */
object IncrementalHistoryLoader {
    private const val TAG = "IncrementalHistoryLoader"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private data class RoomAnchor(
        val id: String?,
        val xmppId: String?,
        val timestamp: Long?
    )

    private fun getRoomAnchor(room: Room): RoomAnchor? {
        val cachedLast = MessageStore.getMessagesForRoom(room.jid).maxByOrNull { it.timestamp ?: it.date.time }
        val timestamp = cachedLast?.timestamp ?: room.lastMessageTimestamp
        if (cachedLast == null && timestamp == null) return null
        return RoomAnchor(
            id = cachedLast?.id,
            xmppId = cachedLast?.xmppId,
            timestamp = timestamp
        )
    }

    private fun hasAnchor(messages: List<com.ethora.chat.core.models.Message>, anchor: RoomAnchor): Boolean {
        return messages.any { message ->
            val messageTs = message.timestamp ?: message.date.time
            (anchor.id != null && message.id == anchor.id) ||
                (anchor.xmppId != null && message.xmppId == anchor.xmppId) ||
                (anchor.timestamp != null && messageTs == anchor.timestamp)
        }
    }
    
    suspend fun updateMessagesTillLast(
        xmppClient: XMPPClient?,
        batchSize: Int = 5,
        maxFetchAttempts: Int = 4,
        messagesPerFetch: Int = 5
    ) {
        if (xmppClient == null) {
            Log.w(TAG, "XMPP client is null, cannot load messages")
            return
        }
        
        val rooms = RoomStore.rooms.value
        if (rooms.isEmpty()) {
            return
        }
        
        var processedIndex = 0
        
        while (processedIndex < rooms.size) {
            val currentBatch = rooms.slice(processedIndex until minOf(processedIndex + batchSize, rooms.size))
            
            val anchorsByJid = currentBatch.associate { room ->
                room.jid to getRoomAnchor(room)
            }
            
            currentBatch.mapIndexed { index, room ->
                scope.launch {
                    try {
                        if (index > 0) {
                            delay(125)
                        }
                        
                        try {
                            // Only send presence if fully connected, but continue with message loading regardless
                            if (xmppClient.isFullyConnected()) {
                                xmppClient.sendPresenceInRoom(room.jid)
                                delay(100)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send presence to ${room.jid}", e)
                        }
                        
                        val anchor = anchorsByJid[room.jid]
                        if (anchor == null) {
                            return@launch
                        }
                        
                        var counter = 0
                        var isAnchorFound = false
                        var currentJidNewMessages = mutableListOf<com.ethora.chat.core.models.Message>()
                        
                        while (!isAnchorFound && counter < maxFetchAttempts) {
                            val lastMessageId = if (counter > 0) {
                                currentJidNewMessages.firstOrNull()?.id
                            } else {
                                null
                            }
                            
                            val fetchedMessages = xmppClient.getHistory(
                                room.jid,
                                max = messagesPerFetch,
                                beforeMessageId = lastMessageId?.toString()
                            )
                            
                            if (fetchedMessages.isEmpty()) {
                                break
                            }
                            
                            counter++
                            currentJidNewMessages = (fetchedMessages + currentJidNewMessages).toMutableList()

                            isAnchorFound = hasAnchor(currentJidNewMessages, anchor)
                        }

                        if (currentJidNewMessages.isNotEmpty()) {
                            if (isAnchorFound) {
                                MessageStore.addMessages(room.jid, currentJidNewMessages)
                            } else {
                                // Anchor miss fallback: prefer latest server state to avoid stale local cache.
                                Log.w(TAG, "Anchor miss for ${room.jid}. Replacing room cache with fetched history.")
                                MessageStore.setMessagesForRoom(room.jid, currentJidNewMessages)
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Error processing room ${room.jid}", error)
                    }
                }
            }.forEach { it.join() }
            
            processedIndex += batchSize
        }
    }
}
