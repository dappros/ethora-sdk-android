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
                // Sort by timestamp (oldest first) to match store expectations and ensure takeLast() works correctly
                persistedMessages.sortedBy { it.timestamp ?: it.date.time }
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
     * Set messages for room.
     * Updates room's last message in RoomStore so chat list shows preview on first load.
     */
    fun setMessagesForRoom(roomJid: String, messages: List<Message>) {
        val currentMessages = _messages.value.toMutableMap()
        currentMessages[roomJid] = messages
        _messages.value = currentMessages
        if (messages.isNotEmpty()) {
            updateRoomLastMessage(roomJid, messages)
        }
    }

    /**
     * Add message to room
     * Matches web: bidirectional ID matching (msg.id === message.id || message.xmppId === msg.id || msg.xmppId === message.id)
     * Returns true if a pending message was matched and updated, false otherwise
     */
    fun addMessage(roomJid: String, message: Message): Boolean {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        
        // Match web: bidirectional ID matching for pending messages
        // Check: msg.id === message.id || (message.xmppId && msg.id === message.xmppId) || (msg.xmppId && msg.xmppId === message.id)
        val existingIndex = roomMessages.indexOfFirst { existing ->
            val exactIdMatch = existing.id == message.id
            val incomingXmppIdMatchesExistingId = message.xmppId != null && existing.id == message.xmppId
            val existingXmppIdMatchesIncomingId = existing.xmppId != null && existing.xmppId == message.id
            
            exactIdMatch || incomingXmppIdMatchesExistingId || existingXmppIdMatchesIncomingId
        }
        
        if (existingIndex >= 0) {
            val existingMessage = roomMessages[existingIndex]
            
            // If it's a pending message, update it and clear pending state
            if (existingMessage.pending == true) {
                // Deep merge: keep existing values but update with new message and set pending: false
                val updatedMessage = message.copy(
                    id = existingMessage.id, // Keep original optimistic ID
                    pending = false, // Always set pending to false when confirmed
                    // Preserve upload metadata for media messages
                    location = message.location ?: existingMessage.location,
                    locationPreview = message.locationPreview ?: existingMessage.locationPreview,
                    fileName = message.fileName ?: existingMessage.fileName,
                    mimetype = message.mimetype ?: existingMessage.mimetype,
                    size = message.size ?: existingMessage.size
                )
                roomMessages[existingIndex] = updatedMessage
                android.util.Log.d("MessageStore", "✅ Updated pending message ${existingMessage.id} (matched by ID/xmppId)")
                
                val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
                currentMessages[roomJid] = sorted
                _messages.value = currentMessages
                
                persistMessage(roomJid, updatedMessage)
                updateRoomLastMessage(roomJid, sorted)
                com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
                return true
            } else {
                // Message already exists and is not pending - skip duplicate
                android.util.Log.d("MessageStore", "⚠️ Message ${message.id} already exists (not pending), skipping")
                return false
            }
        }
        
        // No match, add as new message
        roomMessages.add(message)
        android.util.Log.d("MessageStore", "✅ Added new message ${message.id}")
        
        val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
        currentMessages[roomJid] = sorted
        _messages.value = currentMessages
        
        persistMessage(roomJid, message)
        updateRoomLastMessage(roomJid, sorted)
        com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
        return false
    }
    
    /**
     * Find and update pending message by matching content and user
     * Matches Swift: aggressive matching for messages from current user
     * - For text: match by body + user + timestamp window
     * - For media: match by location/fileName + user + timestamp window
     * Returns true if a pending message was matched and updated, false otherwise
     */
    fun updatePendingMessage(roomJid: String, receivedMessage: Message): Boolean {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()
        
        val now = System.currentTimeMillis()
        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val isFromCurrentUser = currentUser != null && (
            receivedMessage.user.id == currentUser.id ||
            receivedMessage.user.xmppUsername == currentUser.xmppUsername ||
            receivedMessage.user.id == currentUser.xmppUsername ||
            receivedMessage.user.xmppUsername == currentUser.id
        )
        
        // Find pending message - match by content (text or media)
        val pendingIndex = roomMessages.indexOfFirst { existing ->
            if (existing.pending != true) return@indexOfFirst false
            if ((now - existing.date.time) > 30000) return@indexOfFirst false // Within 30 seconds (increased for media uploads)
            
            // Match by user (more flexible matching)
            val existingUserId = existing.user.id.lowercase()
            val receivedUserId = receivedMessage.user.id.lowercase()
            val existingXmpp = existing.user.xmppUsername?.split("@")?.firstOrNull()?.lowercase()
            val receivedXmpp = receivedMessage.user.xmppUsername?.split("@")?.firstOrNull()?.lowercase()
            
            val userMatch = existingUserId == receivedUserId ||
                          (existingXmpp != null && existingXmpp == receivedXmpp) ||
                          (existingXmpp != null && existingXmpp == receivedUserId) ||
                          (receivedXmpp != null && receivedXmpp == existingUserId)
            
            if (!userMatch) return@indexOfFirst false
            
            // For media messages: match by location or fileName
            val isMedia = existing.isMediafile == "true" || existing.location != null || existing.fileName != null || existing.body == "media"
            val receivedIsMedia = receivedMessage.isMediafile == "true" || receivedMessage.location != null || receivedMessage.fileName != null || receivedMessage.body == "media"
            
            if (isMedia || receivedIsMedia) {
                // Media matching: check location, locationPreview, or fileName
                // Also match if both have "media" body and same user
                val locationMatch = existing.location != null && 
                                   receivedMessage.location != null && 
                                   existing.location == receivedMessage.location
                val locationPreviewMatch = existing.locationPreview != null && 
                                          receivedMessage.locationPreview != null && 
                                          existing.locationPreview == receivedMessage.locationPreview
                val fileNameMatch = existing.fileName != null && 
                                   receivedMessage.fileName != null && 
                                   existing.fileName == receivedMessage.fileName
                val mediaBodyMatch = existing.body == "media" && receivedMessage.body == "media"
                
                locationMatch || locationPreviewMatch || fileNameMatch || mediaBodyMatch
            } else {
                // Text matching: match by body content
                existing.body == receivedMessage.body
            }
        }
        
        if (pendingIndex >= 0) {
            val pendingMessage = roomMessages[pendingIndex]
            
            // Check if receivedMessage was already added by addMessage (different ID)
            // If so, remove it to avoid duplicate
            val duplicateIndex = roomMessages.indexOfFirst { it.id == receivedMessage.id && it.id != pendingMessage.id }
            if (duplicateIndex >= 0) {
                android.util.Log.d("MessageStore", "🗑️ Removing duplicate message ${receivedMessage.id} that was added before matching pending message")
                roomMessages.removeAt(duplicateIndex)
            }
            
            // Update pending message with received message data, keeping the original ID
            // Deep merge: keep existing values but update with new message and set pending: false
            val updatedMessage = receivedMessage.copy(
                id = pendingMessage.id, // Keep original optimistic ID
                pending = false,
                // Preserve upload metadata if present
                location = receivedMessage.location ?: pendingMessage.location,
                locationPreview = receivedMessage.locationPreview ?: pendingMessage.locationPreview,
                fileName = receivedMessage.fileName ?: pendingMessage.fileName,
                mimetype = receivedMessage.mimetype ?: pendingMessage.mimetype
            )
            roomMessages[pendingIndex] = updatedMessage
            android.util.Log.d("MessageStore", "✅ Matched pending message ${pendingMessage.id} with received message ${receivedMessage.id} (${if (isFromCurrentUser) "current user" else "content match"})")
            
            val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            
            persistMessage(roomJid, updatedMessage)
            updateRoomLastMessage(roomJid, sorted)
            com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
            return true
        } else {
            // No matching pending message found
            // Don't add as new here - addMessage should have already handled that
            // This prevents duplicates when addMessage adds the message but matching fails
            android.util.Log.d("MessageStore", "⚠️ No pending message matched for ${receivedMessage.id}, message should have been added by addMessage")
            return false
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
            
            // Update pending count
            com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
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
