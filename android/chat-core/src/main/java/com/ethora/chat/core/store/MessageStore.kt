package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.persistence.MessageCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Message store for managing messages state
 * Persists messages to Room Database (matches web: redux-persist with localStorage)
 */
object MessageStore {
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()
    
    // Message cache for persistence
    private var messageCache: MessageCache? = null
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Initialize with MessageCache for persistence
     */
    fun initialize(cache: MessageCache) {
        messageCache = cache
        android.util.Log.d("MessageStore", "✅ MessageStore initialized with MessageCache")
    }
    
    /**
     * Load messages from persistence for a room
     */
    suspend fun loadMessagesFromPersistence(roomJid: String): List<Message> {
        return try {
            val cache = messageCache
            if (cache != null) {
                // Load last 50 messages (matches web: limitMessagesTransform)
                val persistedMessages = cache.getLatestMessages(roomJid, 50)
                android.util.Log.d("MessageStore", "📂 Loaded ${persistedMessages.size} messages from persistence for $roomJid")
                persistedMessages
            } else {
                android.util.Log.w("MessageStore", "⚠️ MessageCache not initialized, cannot load from persistence")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageStore", "❌ Error loading messages from persistence", e)
            emptyList()
        }
    }
    
    /**
     * Persist message to Room Database (background, non-blocking)
     */
    private fun persistMessage(roomJid: String, message: Message) {
        val cache = messageCache ?: return
        persistenceScope.launch {
            try {
                cache.saveMessage(message)
                // Limit to last 50 messages per room (matches web: limitMessagesTransform)
                val currentMessages = _messages.value[roomJid] ?: emptyList()
                if (currentMessages.size > 50) {
                    // Remove oldest messages from persistence
                    val messagesToKeep = currentMessages.takeLast(50)
                    // Clear and re-save last 50
                    cache.clearMessagesForRoom(roomJid)
                    cache.saveMessages(messagesToKeep)
                }
            } catch (e: Exception) {
                android.util.Log.e("MessageStore", "❌ Error persisting message", e)
            }
        }
    }
    
    /**
     * Persist multiple messages to Room Database (background, non-blocking)
     */
    private fun persistMessages(roomJid: String, messages: List<Message>) {
        val cache = messageCache ?: return
        persistenceScope.launch {
            try {
                // Limit to last 50 messages per room (matches web: limitMessagesTransform)
                val messagesToSave = if (messages.size > 50) {
                    messages.takeLast(50)
                } else {
                    messages
                }
                cache.saveMessages(messagesToSave)
            } catch (e: Exception) {
                android.util.Log.e("MessageStore", "❌ Error persisting messages", e)
            }
        }
    }

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
     * If a pending message with matching ID exists, it will be updated instead of adding a duplicate
     */
    fun addMessage(roomJid: String, message: Message) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        
        // Check if there's a pending message with the same ID
        val pendingIndex = roomMessages.indexOfFirst { it.id == message.id && it.pending == true }
        if (pendingIndex >= 0) {
            // Update pending message with real message data (set pending to false)
            val updatedMessage = message.copy(pending = false)
            roomMessages[pendingIndex] = updatedMessage
            android.util.Log.d("MessageStore", "✅ Updated pending message ${message.id} with real message data")
        } else if (!roomMessages.any { it.id == message.id }) {
            // No existing message with this ID, add it
            roomMessages.add(message)
            android.util.Log.d("MessageStore", "✅ Added new message ${message.id}")
        } else {
            android.util.Log.d("MessageStore", "⚠️ Message ${message.id} already exists, skipping")
            return
        }
        
        val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
        currentMessages[roomJid] = sorted
        _messages.value = currentMessages
        
        // Persist to Room Database (background)
        persistMessage(roomJid, message)
        
        // Update room's last message
        updateRoomLastMessage(roomJid, sorted)
    }
    
    /**
     * Find and update pending message by matching content and user
     * Used when we receive a message that might match a pending one by content
     */
    fun updatePendingMessage(roomJid: String, receivedMessage: Message) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        
        // Find pending message with matching body and user (within last 10 seconds)
        val now = System.currentTimeMillis()
        val pendingIndex = roomMessages.indexOfFirst { existing ->
            existing.pending == true &&
            existing.body == receivedMessage.body &&
            existing.user.id == receivedMessage.user.id &&
            (now - existing.date.time) < 10000 // Within 10 seconds
        }
        
        if (pendingIndex >= 0) {
            val pendingMessage = roomMessages[pendingIndex]
            // Update pending message with received message data, keeping the original ID
            val updatedMessage = receivedMessage.copy(
                id = pendingMessage.id, // Keep original optimistic ID
                pending = false
            )
            roomMessages[pendingIndex] = updatedMessage
            android.util.Log.d("MessageStore", "✅ Matched pending message ${pendingMessage.id} with received message ${receivedMessage.id}")
            
            val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            
            // Persist to Room Database (background)
            persistMessage(roomJid, updatedMessage)
            
            // Update room's last message
            updateRoomLastMessage(roomJid, sorted)
        } else {
            // No matching pending message, add as new
            addMessage(roomJid, receivedMessage)
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
            
            // Persist to Room Database (background)
            persistMessages(roomJid, sorted)
            
            // Update room's last message
            updateRoomLastMessage(roomJid, sorted)
        } else {
            android.util.Log.d("MessageStore", "   ⚠️ No new messages to add (all duplicates)")
        }
    }
    
    /**
     * Update room's last message based on messages in store
     * Skip deleted messages when determining last message
     */
    private fun updateRoomLastMessage(roomJid: String, messages: List<Message>) {
        if (messages.isEmpty()) {
            android.util.Log.d("MessageStore", "   ⚠️ No messages to update last message for $roomJid")
            return
        }
        
        // Get the last non-deleted message (newest) - in sorted list, last item is newest
        val lastMessage = messages.lastOrNull { it.isDeleted != true } 
            ?: messages.lastOrNull() // Fallback to last message if all are deleted
        
        if (lastMessage == null) {
            android.util.Log.d("MessageStore", "   ⚠️ No valid last message for $roomJid")
            return
        }
        
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
            
            // Update unread count for this room
            com.ethora.chat.core.store.RoomStore.updateUnreadCount(roomJid, messages)
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
        // Clear persistence (background)
        persistenceScope.launch {
            messageCache?.clearAllMessages()
        }
    }
    
    /**
     * Get message cache (for LogoutService)
     */
    internal fun getMessageCache(): MessageCache? {
        return messageCache
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
    
    /**
     * Edit message
     * Matches web: editRoomMessage action
     */
    fun editMessage(roomJid: String, messageId: String, newText: String) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        val index = roomMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val originalMessage = roomMessages[index]
            val updatedMessage = originalMessage.copy(body = newText)
            roomMessages[index] = updatedMessage
            val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            android.util.Log.d("MessageStore", "✏️ Edited message $messageId in $roomJid")
            
            // Persist to Room Database (background)
            persistMessage(roomJid, updatedMessage)
            
            updateRoomLastMessage(roomJid, sorted)
        } else {
            android.util.Log.w("MessageStore", "⚠️ Message $messageId not found in $roomJid to edit")
        }
    }
    
    /**
     * Add or update reaction to message
     * Matches web: setReactions action
     */
    fun updateReaction(roomJid: String, messageId: String, from: String, reactions: List<String>, data: Map<String, String>) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        val index = roomMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val originalMessage = roomMessages[index]
            val fromId = from.split("@").firstOrNull() ?: from
            
            // Get existing reactions or create new map
            val existingReactions = originalMessage.reaction?.toMutableMap() ?: mutableMapOf()
            
            if (reactions.isEmpty()) {
                // Remove reaction if empty list
                existingReactions.remove(fromId)
            } else {
                // Add or update reaction
                val reactionData = com.ethora.chat.core.models.ReactionMessage(
                    emoji = reactions,
                    data = data
                )
                existingReactions[fromId] = reactionData
            }
            
            val updatedMessage = originalMessage.copy(reaction = existingReactions)
            roomMessages[index] = updatedMessage
            val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            android.util.Log.d("MessageStore", "😀 Updated reaction for message $messageId in $roomJid: from=$fromId, reactions=$reactions")
            
            // Persist to Room Database (background)
            persistMessage(roomJid, updatedMessage)
        } else {
            android.util.Log.w("MessageStore", "⚠️ Message $messageId not found in $roomJid to add reaction")
        }
    }
    
    /**
     * Mark message as deleted
     * Matches web: deleteRoomMessage action (but we keep the message with isDeleted flag)
     */
    fun markMessageAsDeleted(roomJid: String, messageId: String) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        val index = roomMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val originalMessage = roomMessages[index]
            val updatedMessage = originalMessage.copy(
                body = "This message was deleted.", // Update body for UI
                isDeleted = true,
                isMediafile = null, // Clear media info
                location = null,
                locationPreview = null,
                fileName = null,
                mimetype = null,
                originalName = null,
                reaction = null, // Clear reactions
                reply = null // Clear replies
            )
            roomMessages[index] = updatedMessage
            currentMessages[roomJid] = roomMessages
            _messages.value = currentMessages
            android.util.Log.d("MessageStore", "🗑️ Marked message $messageId in $roomJid as deleted.")
            
            // Persist to Room Database (background)
            persistMessage(roomJid, updatedMessage)
            
            updateRoomLastMessage(roomJid, roomMessages) // Update last message in room list
        } else {
            android.util.Log.w("MessageStore", "⚠️ Message $messageId not found in $roomJid to mark as deleted.")
        }
    }
}
