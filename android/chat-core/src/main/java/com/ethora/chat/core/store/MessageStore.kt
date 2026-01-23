package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Message store for managing messages state
 */
object MessageStore {
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    /**
     * Get messages for room
     */
    fun getMessagesForRoom(roomJid: String): List<Message> {
        return _messages.value[roomJid] ?: emptyList()
    }

    /**
     * Set messages for room
     */
    fun setMessagesForRoom(roomJid: String, messages: List<Message>) {
        val currentMessages = _messages.value.toMutableMap()
        currentMessages[roomJid] = messages
        _messages.value = currentMessages
    }

    /**
     * Add message to room
     */
    fun addMessage(roomJid: String, message: Message) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        if (!roomMessages.any { it.id == message.id }) {
            roomMessages.add(message)
            val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            
            // Update room's last message
            updateRoomLastMessage(roomJid, sorted)
        }
    }

    /**
     * Add multiple messages to room
     */
    fun addMessages(roomJid: String, newMessages: List<Message>) {
        android.util.Log.d("MessageStore", "📥 addMessages called for $roomJid with ${newMessages.size} messages")
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        val existingIds = roomMessages.map { it.id }.toSet()
        val messagesToAdd = newMessages.filter { it.id !in existingIds }
        android.util.Log.d("MessageStore", "   Existing messages: ${roomMessages.size}, New unique: ${messagesToAdd.size}")
        
        if (messagesToAdd.isNotEmpty()) {
            roomMessages.addAll(messagesToAdd)
            // Sort by timestamp (oldest first) - this ensures reverse layout shows newest at bottom
            val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            android.util.Log.d("MessageStore", "   ✅ Added messages, total for room: ${sorted.size}")
            android.util.Log.d("MessageStore", "   First message timestamp: ${sorted.firstOrNull()?.timestamp}, Last: ${sorted.lastOrNull()?.timestamp}")
            
            // Update room's last message
            updateRoomLastMessage(roomJid, sorted)
        } else {
            android.util.Log.d("MessageStore", "   ⚠️ No new messages to add (all duplicates)")
        }
    }
    
    /**
     * Update room's last message based on messages in store
     */
    private fun updateRoomLastMessage(roomJid: String, messages: List<Message>) {
        if (messages.isEmpty()) {
            android.util.Log.d("MessageStore", "   ⚠️ No messages to update last message for $roomJid")
            return
        }
        
        // Get the last message (newest) - in sorted list, last item is newest
        val lastMessage = messages.lastOrNull() ?: return
        
        // Create LastMessage from Message
        val lastMessageModel = com.ethora.chat.core.models.LastMessage(
            body = lastMessage.body,
            date = lastMessage.date,
            emoji = lastMessage.reaction?.values?.firstOrNull()?.emoji?.firstOrNull(),
            locationPreview = lastMessage.locationPreview,
            filename = lastMessage.fileName,
            mimetype = lastMessage.mimetype,
            originalName = lastMessage.originalName
        )
        
        // Update room in RoomStore
        val room = com.ethora.chat.core.store.RoomStore.getRoomByJid(roomJid)
        
        room?.let {
            val updatedRoom = it.copy(
                lastMessage = lastMessageModel,
                lastMessageTimestamp = lastMessage.timestamp ?: lastMessage.date.time
            )
            com.ethora.chat.core.store.RoomStore.updateRoom(updatedRoom)
            android.util.Log.d("MessageStore", "   ✅ Updated last message for room $roomJid: ${lastMessage.body.take(50)}")
        } ?: run {
            android.util.Log.w("MessageStore", "   ⚠️ Room not found for JID: $roomJid")
        }
    }

    /**
     * Update message
     */
    fun updateMessage(roomJid: String, message: Message) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        val index = roomMessages.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            roomMessages[index] = message
            currentMessages[roomJid] = roomMessages
            _messages.value = currentMessages
        }
    }

    /**
     * Remove message
     */
    fun removeMessage(roomJid: String, messageId: String) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        roomMessages.removeAll { it.id == messageId }
        currentMessages[roomJid] = roomMessages
        _messages.value = currentMessages
    }

    /**
     * Clear messages for room
     */
    fun clearMessagesForRoom(roomJid: String) {
        val currentMessages = _messages.value.toMutableMap()
        currentMessages.remove(roomJid)
        _messages.value = currentMessages
    }

    /**
     * Clear all messages
     */
    fun clear() {
        _messages.value = emptyMap()
    }
    
    /**
     * Sync last messages for all rooms that have messages
     * Call this after initial history load to update room list
     */
    fun syncLastMessagesForAllRooms() {
        android.util.Log.d("MessageStore", "🔄 Syncing last messages for all rooms...")
        _messages.value.forEach { (roomJid, messages) ->
            if (messages.isNotEmpty()) {
                updateRoomLastMessage(roomJid, messages)
            }
        }
    }
}
