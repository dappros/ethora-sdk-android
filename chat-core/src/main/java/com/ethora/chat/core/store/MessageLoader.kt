package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Global message loader - loads initial messages for all rooms once
 * Similar to updateMessagesTillLast in web version
 */
object MessageLoader {
    private var hasSyncedHistory = false
    private const val TAG = "MessageLoader"
    
    /**
     * Load initial messages for all rooms
     * This should be called once after XMPP client is initialized and rooms are loaded
     * Similar to updateMessagesTillLast in web version
     */
    suspend fun loadInitialMessagesForAllRooms(
        xmppClient: XMPPClient?,
        batchSize: Int = 5,
        messagesPerRoom: Int = 30
    ) {
        if (hasSyncedHistory) {
            Log.d(TAG, "⏭️ History already synced, skipping")
            return
        }
        
        if (xmppClient == null) {
            Log.w(TAG, "⚠️ XMPP client is null, cannot load messages")
            return
        }
        
        val rooms = RoomStore.rooms.value
        if (rooms.isEmpty()) {
            Log.d(TAG, "⏭️ No rooms to load messages for")
            hasSyncedHistory = true
            return
        }
        
        Log.d(TAG, "🔄 Starting initial message load for ${rooms.size} rooms")
        
        // Process rooms in batches to avoid overwhelming the server
        var processedIndex = 0
        while (processedIndex < rooms.size) {
            val currentBatch = rooms.slice(processedIndex until minOf(processedIndex + batchSize, rooms.size))
            
            // Load messages for each room in the batch
            currentBatch.forEachIndexed { index, room ->
                try {
                    // Add delay between rooms (except first one) to avoid rate limiting
                    if (index > 0) {
                        delay(125) // 125ms delay between rooms, matching web version
                    }
                    
                    // Check if we already have messages for this room
                    val existingMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                    if (existingMessages.isNotEmpty()) {
                        Log.d(TAG, "   ⏭️ Room ${room.jid} already has ${existingMessages.size} messages, skipping")
                        return@forEachIndexed
                    }
                    
                    Log.d(TAG, "   📥 Loading messages for room: ${room.jid}")
                    val history = xmppClient.getHistory(room.jid, max = messagesPerRoom)
                    
                    if (history.isNotEmpty()) {
                        MessageStore.addMessages(room.jid, history)
                        Log.d(TAG, "   ✅ Loaded ${history.size} messages for ${room.jid}")
                    } else {
                        Log.d(TAG, "   ⚠️ No messages found for ${room.jid}")
                    }
                } catch (e: CancellationException) {
                    // Re-throw cancellation exceptions (expected when composable leaves composition)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "   ❌ Error loading messages for room ${room.jid}", e)
                }
            }
            
            processedIndex += batchSize
        }
        
        hasSyncedHistory = true
        Log.d(TAG, "✅ Finished initial message load for all rooms")
    }
    
    /**
     * Reset the sync flag (useful for testing or re-initialization)
     */
    fun reset() {
        hasSyncedHistory = false
    }
}
