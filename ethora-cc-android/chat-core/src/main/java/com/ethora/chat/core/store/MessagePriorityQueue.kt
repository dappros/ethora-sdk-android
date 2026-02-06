package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.PriorityQueue

private data class QueuedRoom(
    val jid: String,
    var priority: Int,
    val lastMessageId: String?,
    var messageCount: Int,
    val messages: MutableList<com.ethora.chat.core.models.Message> = mutableListOf(),
    var retryCount: Int = 0
)

/**
 * Message priority queue - processes rooms with priority
 * Process 5 rooms at once (250ms interval)
 * Load 20 messages per room per request
 * Re-queue rooms if messageCount < 30 and !historyComplete
 * Trim messages to 30 when room has >= 30 messages
 */
class MessagePriorityQueue(
    private val xmppClient: XMPPClient?,
    private val activeRoomJid: String? = null
) {
    private val TAG = "MessagePriorityQueue"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val messageQueue = PriorityQueue<QueuedRoom> { a, b -> b.priority - a.priority }
    private val processedRooms = mutableSetOf<String>()
    private var job: kotlinx.coroutines.Job? = null
    
    private fun addRoomToProcessed(queueRoom: QueuedRoom, rooms: Map<String, Room>) {
        if (queueRoom.messageCount >= 20 && !processedRooms.contains(queueRoom.jid)) {
            processedRooms.add(queueRoom.jid)
            
            val room = rooms[queueRoom.jid]
            val messages = MessageStore.getMessagesForRoom(queueRoom.jid)
            if (messages.size >= 30) {
                val trimmed = messages.takeLast(30)
                MessageStore.setMessagesForRoom(queueRoom.jid, trimmed)
            }
        }
    }
    
    private suspend fun processQueue() {
        if (messageQueue.isEmpty()) {
            return
        }
        
        val rooms = RoomStore.rooms.value.associateBy { it.jid }
        
        val roomsToProcess = mutableListOf<QueuedRoom>()
        repeat(5) {
            val room = messageQueue.poll()
            if (room != null) {
                roomsToProcess.add(room)
            }
        }
        
        if (roomsToProcess.isEmpty()) {
            return
        }
        
        roomsToProcess.map { room ->
            scope.launch {
                try {
                    val currentRoom = rooms[room.jid]
                    if (room.messageCount < 30 && currentRoom?.historyComplete != true) {
                        try {
                            // Only send presence if fully connected, but continue with message loading regardless
                            if (xmppClient?.isFullyConnected() == true) {
                                xmppClient.sendPresenceInRoom(room.jid)
                                kotlinx.coroutines.delay(100)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send presence to ${room.jid}", e)
                        }
                        
                        RoomStore.setRoomLoading(room.jid, true)
                        
                        // CRITICAL: For initial load (messageCount == 0), use beforeMessageId = null 
                        // to get LATEST messages (matches web: empty <before/> tag)
                        // For subsequent loads, use lastMessageId to paginate older messages
                        val beforeMessageId = if (room.messageCount == 0) {
                            null  // Get latest messages
                        } else {
                            room.lastMessageId  // Paginate older messages
                        }
                        
                        val newMessages = xmppClient?.getHistory(
                            room.jid,
                            max = 20,
                            beforeMessageId = beforeMessageId?.toString()
                        ) ?: emptyList()
                        
                        room.messages.addAll(newMessages)
                        
                        if (newMessages.isNotEmpty()) {
                            MessageStore.addMessages(room.jid, newMessages)
                        }
                        
                        if (room.messageCount + newMessages.size < 30 && 
                            currentRoom?.historyComplete != true) {
                            val updatedRoom = QueuedRoom(
                                jid = room.jid,
                                priority = room.priority,
                                lastMessageId = newMessages.firstOrNull()?.id,
                                messageCount = room.messageCount + newMessages.size,
                                messages = room.messages
                            )
                            messageQueue.offer(updatedRoom)
                        }
                    }
                    
                    addRoomToProcessed(room, rooms)
                } catch (error: Exception) {
                    Log.e(TAG, "Error processing room: ${room.jid} (retry ${room.retryCount + 1}/3)", error)
                    
                    // Only retry up to 3 times to prevent infinite loops
                    if (room.retryCount < 3) {
                        val retryRoom = QueuedRoom(
                            jid = room.jid,
                            priority = (room.priority - 1).coerceAtLeast(1),
                            lastMessageId = room.lastMessageId,
                            messageCount = room.messageCount,
                            messages = room.messages,
                            retryCount = room.retryCount + 1
                        )
                        messageQueue.offer(retryRoom)
                    } else {
                        Log.e(TAG, "❌ Max retries reached for room ${room.jid}, giving up")
                        // Mark as processed to prevent further attempts
                        addRoomToProcessed(room, rooms)
                    }
                } finally {
                    RoomStore.setRoomLoading(room.jid, false)
                }
            }
        }.forEach { it.join() }
    }
    
    fun initialize(rooms: List<Room>) {
        messageQueue.clear()
        processedRooms.clear()
        
        rooms.forEach { room ->
            val messages = MessageStore.getMessagesForRoom(room.jid)
            val priority = if (room.jid == activeRoomJid) 100 else 50
            
            messageQueue.offer(
                QueuedRoom(
                    jid = room.jid,
                    priority = priority,
                    lastMessageId = messages.firstOrNull()?.id,
                    messageCount = messages.size,
                    messages = messages.toMutableList()
                )
            )
        }
    }
    
    fun start() {
        if (job?.isActive == true) {
            return
        }
        
        job = scope.launch {
            while (true) {
                processQueue()
                delay(250)
            }
        }
    }
    
    fun stop() {
        job?.cancel()
        job = null
    }
    
    fun isRunning(): Boolean {
        return job?.isActive == true
    }
}
