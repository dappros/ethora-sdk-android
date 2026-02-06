package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Message loader queue - processes rooms in batches
 * Processes rooms in batches: batchSize=5, pageSize=10, pollInterval=1000ms
 * Tracks processed rooms to avoid duplicate loads
 * Respects historyComplete flag from rooms
 */
class MessageLoaderQueue(
    private val xmppClient: XMPPClient?,
    private val batchSize: Int = 5,
    private val pageSize: Int = 10,
    private val pollInterval: Long = 1000L
) {
    private val TAG = "MessageLoaderQueue"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processedRooms = mutableSetOf<String>()
    private var isProcessing = false
    private var job: kotlinx.coroutines.Job? = null
    
    private fun roomHasMoreMessages(room: Room, max: Int = 20): Boolean {
        val messageCount = MessageStore.getMessagesForRoom(room.jid).size
        return messageCount < max
    }
    
    private suspend fun processQueue() {
        if (isProcessing) return
        
        val rooms = RoomStore.rooms.value
        if (rooms.isEmpty()) {
            return
        }
        
        val unprocessed = rooms.filter { room ->
            !processedRooms.contains(room.jid) &&
            roomHasMoreMessages(room) &&
            room.noMessages != true &&
            room.historyComplete != true
        }
        
        if (unprocessed.isEmpty()) {
            stop()
            return
        }
        
        isProcessing = true
        
        try {
            for (i in unprocessed.indices step batchSize) {
                val batch = unprocessed.slice(i until minOf(i + batchSize, unprocessed.size))
                
                batch.forEachIndexed { index, room ->
                    scope.launch {
                        try {
                            if (index > 0) {
                                delay(200)
                            }
                            
                            if (roomHasMoreMessages(room) && 
                                room.noMessages != true && 
                                room.historyComplete != true) {
                                
                                try {
                                    xmppClient?.sendPresenceInRoom(room.jid)
                                    delay(100)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to send presence to ${room.jid}", e)
                                }
                                
                                RoomStore.setRoomLoading(room.jid, true)
                                
                                try {
                                    val history = xmppClient?.getHistory(room.jid, max = pageSize, beforeMessageId = null)
                                    if (history != null && history.isNotEmpty()) {
                                        MessageStore.addMessages(room.jid, history)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error loading messages for ${room.jid}", e)
                                } finally {
                                    RoomStore.setRoomLoading(room.jid, false)
                                }
                            }
                            
                            processedRooms.add(room.jid)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing room ${room.jid}", e)
                        }
                    }
                }
                
                delay(200)
            }
        } finally {
            isProcessing = false
        }
    }
    
    fun start() {
        if (job?.isActive == true) {
            return
        }
        
        job = scope.launch {
            while (true) {
                processQueue()
                delay(pollInterval)
            }
        }
    }
    
    fun stop() {
        job?.cancel()
        job = null
        isProcessing = false
    }
    
    fun reset() {
        processedRooms.clear()
    }
    
    fun isRunning(): Boolean {
        return job?.isActive == true
    }
}
