package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.store.RoomsPresenceInitializer
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global message loader - loads initial messages for all rooms once
 * Similar to updateMessagesTillLast in web version
 * Supports incremental sync after offline
 */
object MessageLoader {
    @Volatile private var hasSyncedHistory = false
    @Volatile private var syncInProgress = false
    private const val TAG = "MessageLoader"
    
    private var localStorage: LocalStorage? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isSynced(): Boolean = hasSyncedHistory
    fun isSyncInProgress(): Boolean = syncInProgress
    
    /**
     * Initialize with LocalStorage for sync timestamp tracking
     */
    fun initialize(localStorage: LocalStorage) {
        this.localStorage = localStorage
    }
    
    /**
     * Load initial messages for all rooms
     * Matches web: loads 30 messages per room initially
     * This should be called once after XMPP client is initialized and rooms are loaded
     * Similar to updateMessagesTillLast in web version
     */
    suspend fun loadInitialMessagesForAllRooms(
        xmppClient: XMPPClient?,
        activeRoomJid: String? = null,
        batchSize: Int = 5, // Match web: batchSize = 5
        messagesPerRoom: Int = 30 // Match web: 30 messages per room
    ) {
        if (hasSyncedHistory) {
            return
        }
        
        if (xmppClient == null) {
            Log.w(TAG, "XMPP client is null, cannot load messages")
            return
        }
        
        val rooms = RoomStore.rooms.value
        if (rooms.isEmpty()) {
            hasSyncedHistory = true
            return
        }
        
        syncInProgress = true
        
        try {
            RoomsPresenceInitializer.initRoomsPresence(xmppClient, rooms)
            delay(500)
            
            val roomsToLoad = rooms.filter { room ->
                val existingMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                existingMessages.isEmpty()
            }
            
            if (roomsToLoad.isEmpty()) {
                hasSyncedHistory = true
                return
            }
            
            val prioritizedRooms = if (activeRoomJid != null) {
                val activeRoom = roomsToLoad.find { it.jid == activeRoomJid }
                if (activeRoom != null) {
                    listOf(activeRoom) + roomsToLoad.filter { it.jid != activeRoomJid }
                } else {
                    roomsToLoad
                }
            } else {
                roomsToLoad
            }
            
            if (activeRoomJid != null && prioritizedRooms.isNotEmpty() && prioritizedRooms[0].jid == activeRoomJid) {
                val activeRoom = prioritizedRooms[0]
                try {
                    val history = xmppClient.getHistory(activeRoom.jid, max = messagesPerRoom, beforeMessageId = null)
                    if (history.isNotEmpty()) {
                        MessageStore.addMessages(activeRoom.jid, history)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading messages for active room ${activeRoom.jid}", e)
                }
            }
            
            val remainingRooms = if (activeRoomJid != null && prioritizedRooms.isNotEmpty() && prioritizedRooms[0].jid == activeRoomJid) {
                prioritizedRooms.drop(1)
            } else {
                prioritizedRooms
            }
            
            scope.launch {
                for ((index, room) in remainingRooms.withIndex()) {
                    try {
                        if (index > 0) {
                            delay(50)
                        }
                        xmppClient.getHistory(room.jid, max = messagesPerRoom, beforeMessageId = null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requesting history for room ${room.jid}", e)
                    }
                }
            }

            hasSyncedHistory = true
            
            localStorage?.let { storage ->
                storage.saveLastSyncTimestamp(System.currentTimeMillis())
            }
        } finally {
            syncInProgress = false
        }
    }
    
    /**
     * Sync messages since last sync timestamp (for offline recovery)
     * Called when app comes back online
     */
    suspend fun syncMessagesSince(
        xmppClient: XMPPClient?,
        sinceTimestamp: Long? = null,
        batchSize: Int = 5,
        messagesPerRoom: Int = 30
    ) {
        if (xmppClient == null) {
            Log.w(TAG, "XMPP client is null, cannot sync messages")
            return
        }
        
        val rooms = RoomStore.rooms.value
        if (rooms.isEmpty()) {
            return
        }
        
        val lastSync = sinceTimestamp ?: localStorage?.getLastSyncTimestamp() ?: 0L
        
        syncInProgress = true
        
        try {
            var processedIndex = 0
            while (processedIndex < rooms.size) {
                val currentBatch = rooms.slice(processedIndex until minOf(processedIndex + batchSize, rooms.size))

                currentBatch.forEachIndexed { index, room ->
                    try {
                        if (index > 0) {
                            delay(125)
                        }

                        val history = xmppClient.getHistory(room.jid, max = messagesPerRoom, beforeMessageId = null)

                        if (history.isNotEmpty()) {
                            val newMessages = history.filter { message ->
                                (message.timestamp ?: message.date.time) > lastSync
                            }
                            
                            if (newMessages.isNotEmpty()) {
                                MessageStore.addMessages(room.jid, newMessages)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing messages for room ${room.jid}", e)
                    }
                }

                processedIndex += batchSize
            }

            localStorage?.let { storage ->
                storage.saveLastSyncTimestamp(System.currentTimeMillis())
            }
        } finally {
            syncInProgress = false
        }
    }
    
    /**
     * Reset the sync flag (useful for testing or re-initialization)
     */
    fun reset() {
        hasSyncedHistory = false
        syncInProgress = false
    }
}
