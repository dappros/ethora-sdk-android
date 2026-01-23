package com.ethora.chat.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

/**
 * ViewModel for chat room
 */
class ChatRoomViewModel(
    private val room: Room,
    private val xmppClient: XMPPClient?
) : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Loading state comes from store (matches web: rooms[chatJID].isLoading)
    val isLoading: StateFlow<Boolean> = RoomStore.roomLoadingStates
        .map { it[room.jid] ?: false }
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    
    private var isLoadingMoreInProgress = false
    
    private val _hasMoreMessages = MutableStateFlow(true)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _composingUsers = MutableStateFlow<List<String>>(emptyList())
    val composingUsers: StateFlow<List<String>> = _composingUsers.asStateFlow()
    
    val currentUserId: String?
        get() = UserStore.currentUser.value?.id

    val currentUserXmppUsername: String?
        get() = UserStore.currentUser.value?.xmppUsername
    
    // Observe composing state from RoomStore (matches web: useSelector)
    init {
        // Observe messages from store (matches web: useSelector)
        viewModelScope.launch {
            MessageStore.messages
                .map { 
                    val roomMessages = it[room.jid] ?: emptyList()
                    android.util.Log.d("ChatRoomViewModel", "🔄 Store updated, messages for ${room.jid}: ${roomMessages.size}")
                    roomMessages
                }
                .distinctUntilChanged()
                .collect { 
                    android.util.Log.d("ChatRoomViewModel", "📥 Collected ${it.size} messages from store")
                    _messages.value = it
                }
        }
        
        // Observe composing state from RoomStore (matches web: useSelector)
        viewModelScope.launch {
            RoomStore.rooms
                .map { rooms ->
                    val currentRoom = rooms.firstOrNull { it.jid == room.jid }
                    currentRoom?.composingList ?: emptyList()
                }
                .distinctUntilChanged()
                .collect { composingList ->
                    _composingUsers.value = composingList
                    android.util.Log.d("ChatRoomViewModel", "📝 Composing users updated: $composingList")
                }
        }
        
        // Check if messages exist in store - only load if they don't (matches web pattern)
        val storedMessages = MessageStore.messages.value[room.jid] ?: emptyList()
        if (storedMessages.isEmpty()) {
            // Try loading from persistence first
            viewModelScope.launch {
                val persistedMessages = MessageStore.loadMessagesFromPersistence(room.jid)
                if (persistedMessages.isNotEmpty()) {
                    // Load from persistence
                    MessageStore.setMessagesForRoom(room.jid, persistedMessages)
                    _messages.value = persistedMessages
                    android.util.Log.d("ChatRoomViewModel", "✅ Loaded ${persistedMessages.size} messages from persistence")
                } else {
                    // No messages in store or persistence, load them (matches web: setIsLoading({ chatJID, loading: true }))
                    RoomStore.setRoomLoading(room.jid, true)
                    loadMessages()
                }
            }
        } else {
            // Messages already loaded globally, just use them (no loader)
            _messages.value = storedMessages
            android.util.Log.d("ChatRoomViewModel", "✅ Using pre-loaded messages (${storedMessages.size} messages)")
        }
    }
    
    /**
     * Send typing indicator (start composing)
     */
    fun sendStartTyping() {
        viewModelScope.launch {
            val currentUser = UserStore.currentUser.value
            val fullName = "${currentUser?.firstName ?: ""} ${currentUser?.lastName ?: ""}".trim()
            if (fullName.isNotBlank()) {
                xmppClient?.sendTypingIndicator(room.jid, fullName, true)
            }
        }
    }
    
    /**
     * Send typing indicator (stop composing)
     */
    fun sendStopTyping() {
        viewModelScope.launch {
            val currentUser = UserStore.currentUser.value
            val fullName = "${currentUser?.firstName ?: ""} ${currentUser?.lastName ?: ""}".trim()
            if (fullName.isNotBlank()) {
                xmppClient?.sendTypingIndicator(room.jid, fullName, false)
            }
        }
    }

    /**
     * Send message
     */
    fun sendMessage(text: String) {
        viewModelScope.launch {
            val currentUser = UserStore.currentUser.value
            if (currentUser == null) {
                android.util.Log.e("ChatRoomViewModel", "Cannot send message: user is null")
                return@launch
            }
            
            if (text.isBlank()) {
                android.util.Log.w("ChatRoomViewModel", "Cannot send empty message")
                return@launch
            }
            
            android.util.Log.d("ChatRoomViewModel", "📤 Attempting to send message: '$text' to room: ${room.jid}")
            android.util.Log.d("ChatRoomViewModel", "   User: ${currentUser.firstName} ${currentUser.lastName}, xmppUsername: ${currentUser.xmppUsername}")
            android.util.Log.d("ChatRoomViewModel", "   XMPP Client: ${xmppClient != null}, isFullyConnected: ${xmppClient?.isFullyConnected()}")
            
            val messageId = xmppClient?.sendMessage(
                roomJID = room.jid,
                messageBody = text,
                firstName = currentUser.firstName,
                lastName = currentUser.lastName,
                photo = currentUser.profileImage,
                walletAddress = currentUser.walletAddress
            )
            
            if (messageId != null) {
                // Create optimistic message with pending state
                val optimisticMessage = Message(
                    id = messageId,
                    body = text,
                    user = currentUser,
                    date = java.util.Date(),
                    roomJid = room.jid,
                    pending = true,
                    xmppId = messageId,
                    xmppFrom = "${room.jid}/${currentUser.id}"
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                android.util.Log.d("ChatRoomViewModel", "✅ Created optimistic message with pending=true, ID: $messageId")
            } else {
                android.util.Log.e("ChatRoomViewModel", "❌ Failed to send message - sendMessage returned null")
            }
        }
    }

    /**
     * Send media
     */
    fun sendMedia(data: ByteArray, mimeType: String) {
        // Implementation for media sending
    }

    /**
     * Load messages (initial load)
     * Only called when messages don't exist in store
     * Matches web: useRoomInitialization hook
     */
    fun loadMessages() {
        viewModelScope.launch {
            try {
                android.util.Log.d("ChatRoomViewModel", "📥 Loading messages for room: ${room.jid}")
                
                // Set loading state in store (matches web: dispatch(setIsLoading({ chatJID, loading: true })))
                RoomStore.setRoomLoading(room.jid, true)
                
                // Load history from XMPP if client is available
                xmppClient?.let { client ->
                    android.util.Log.d("ChatRoomViewModel", "   Requesting history from XMPP...")
                    val history = client.getHistory(room.jid, max = 30)
                    android.util.Log.d("ChatRoomViewModel", "   Received ${history.size} messages from history")
                    
                    if (history.isNotEmpty()) {
                        // Add to message store - this will trigger the flow observer and update last message
                        MessageStore.addMessages(room.jid, history)
                        android.util.Log.d("ChatRoomViewModel", "   ✅ Added ${history.size} messages to store")
                        
                        // Update hasMoreMessages based on result and historyComplete
                        val updatedRoom = RoomStore.getRoomByJid(room.jid)
                        val historyComplete = updatedRoom?.historyComplete == true
                        _hasMoreMessages.value = !historyComplete && history.size >= 30
                        
                        // If history is complete, set hasMoreMessages to false
                        if (historyComplete) {
                            android.util.Log.d("ChatRoomViewModel", "   ✅ History is complete")
                            _hasMoreMessages.value = false
                        }
                    } else {
                        android.util.Log.w("ChatRoomViewModel", "   ⚠️ History is empty")
                        _hasMoreMessages.value = false
                    }
                } ?: run {
                    android.util.Log.w("ChatRoomViewModel", "   ⚠️ XMPP client is null")
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled - don't log as error
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error loading messages", e)
            } finally {
                // Clear loading state in store (matches web: dispatch(setIsLoading({ chatJID, loading: false })))
                RoomStore.setRoomLoading(room.jid, false)
            }
        }
    }
    
    /**
     * Refresh messages in background without showing loader
     */
    private fun refreshMessagesInBackground() {
        viewModelScope.launch {
            try {
                xmppClient?.let { client ->
                    val history = client.getHistory(room.jid, max = 30)
                    if (history.isNotEmpty()) {
                        // Add messages - this will automatically update last message
                        MessageStore.addMessages(room.jid, history)
                        _hasMoreMessages.value = history.size >= 30
                        android.util.Log.d("ChatRoomViewModel", "   🔄 Refreshed ${history.size} messages in background")
                    }
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled - don't log as error
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error refreshing messages", e)
            }
        }
    }
    
    /**
     * Load more messages (pagination) when scrolling to top
     * In reverse layout, "top" means oldest messages (first in the list)
     * Matches web: loadMoreMessages in ChatRoom.tsx
     */
    fun loadMoreMessages() {
        val currentHasMore = _hasMoreMessages.value
        val currentIsLoading = isLoading.value
        val currentRoom = RoomStore.getRoomByJid(this.room.jid)
        val historyComplete = currentRoom?.historyComplete == true
        
        // Don't load if already loading, no more messages, or history is complete
        if (isLoadingMoreInProgress || !currentHasMore || currentIsLoading || historyComplete) {
            android.util.Log.d("ChatRoomViewModel", "⏭️ Skipping loadMore: isLoadingMore=$isLoadingMoreInProgress, hasMore=$currentHasMore, isLoading=$currentIsLoading, historyComplete=$historyComplete")
            return
        }
        
        viewModelScope.launch {
            isLoadingMoreInProgress = true
            _isLoadingMore.value = true
            try {
                val currentMessages = _messages.value
                if (currentMessages.isEmpty()) {
                    android.util.Log.d("ChatRoomViewModel", "⏭️ No messages to paginate from")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }
                
                // In reverse layout, first message in list is the oldest (at "top")
                // Get the first message (oldest) for pagination
                val oldestMessage = currentMessages.firstOrNull()
                if (oldestMessage == null) {
                    android.util.Log.d("ChatRoomViewModel", "⏭️ No oldest message found")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }
                
                // Use timestamp for pagination (matches web version)
                val beforeTimestamp = oldestMessage.timestamp ?: oldestMessage.date.time
                
                android.util.Log.d("ChatRoomViewModel", "📜 Loading more messages before timestamp: $beforeTimestamp (oldest message: ${oldestMessage.id})")
                
                xmppClient?.let { client ->
                    val history = client.getHistory(room.jid, max = 30, beforeTimestamp = beforeTimestamp)
                    android.util.Log.d("ChatRoomViewModel", "   Received ${history.size} older messages")
                    
                    if (history.isNotEmpty()) {
                        // Add to message store - these will be older messages
                        MessageStore.addMessages(room.jid, history)
                        android.util.Log.d("ChatRoomViewModel", "   ✅ Added ${history.size} older messages to store")
                        
                        // Update hasMoreMessages - if we got less than max, we've reached the end
                        val hasMore = history.size >= 30
                        _hasMoreMessages.value = hasMore
                        
                        // Check if history is complete (from room)
                        val updatedRoom = RoomStore.getRoomByJid(room.jid)
                        if (updatedRoom?.historyComplete == true) {
                            _hasMoreMessages.value = false
                            android.util.Log.d("ChatRoomViewModel", "   ✅ History is complete, no more messages")
                        }
                        
                        // Force UI update to show new messages
                        kotlinx.coroutines.delay(50)
                        val updatedMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                        _messages.value = updatedMessages
                        android.util.Log.d("ChatRoomViewModel", "   ✅ Updated UI with ${updatedMessages.size} total messages")
                        
                        if (!hasMore) {
                            android.util.Log.d("ChatRoomViewModel", "   ✅ Reached end of history (got ${history.size} < 30)")
                        }
                    } else {
                        android.util.Log.d("ChatRoomViewModel", "   ⚠️ No more messages available")
                        _hasMoreMessages.value = false
                    }
                } ?: run {
                    android.util.Log.w("ChatRoomViewModel", "   ⚠️ XMPP client is null")
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled - don't log as error
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error loading more messages", e)
            } finally {
                isLoadingMoreInProgress = false
                _isLoadingMore.value = false
                android.util.Log.d("ChatRoomViewModel", "   Finished loading more, isLoadingMore: false")
            }
        }
    }
    
    /**
     * Send media
     */
    fun sendMedia(file: File, mimeType: String) {
        viewModelScope.launch {
            try {
                val currentUser = UserStore.currentUser.value
                val token = UserStore.token.value
                
                if (currentUser == null || token == null) {
                    android.util.Log.e("ChatRoomViewModel", "Cannot send media: user or token is null")
                    return@launch
                }
                
                // Create optimistic message
                val messageId = "send-media-message:${System.currentTimeMillis()}"
                val optimisticMessage = Message(
                    id = messageId,
                    body = "media",
                    user = currentUser,
                    date = Date(),
                    roomJid = room.jid,
                    pending = true,
                    isDeleted = false,
                    xmppId = messageId,
                    xmppFrom = "${room.jid}/${currentUser.id}",
                    isSystemMessage = "false",
                    isMediafile = "true",
                    fileName = file.name,
                    location = "",
                    locationPreview = "",
                    mimetype = mimeType,
                    originalName = file.name,
                    size = file.length().toString()
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                
                // Upload file
                android.util.Log.d("ChatRoomViewModel", "📤 Uploading file: ${file.name} (${mimeType})")
                val uploadResult = AuthAPIHelper.uploadFile(file, mimeType, token)
                
                if (uploadResult == null) {
                    android.util.Log.e("ChatRoomViewModel", "❌ File upload failed")
                    // Remove optimistic message on failure
                    // TODO: Mark message as failed
                    return@launch
                }
                
                android.util.Log.d("ChatRoomViewModel", "✅ File uploaded: ${uploadResult.location}")
                
                // Prepare media data for XMPP
                val senderJID = currentUser.xmppUsername?.let { "$it@xmpp.ethoradev.com" } ?: ""
                val mediaData = mapOf<String, Any>(
                    "senderJID" to senderJID,
                    "senderFirstName" to (currentUser.firstName ?: ""),
                    "senderLastName" to (currentUser.lastName ?: ""),
                    "senderWalletAddress" to (currentUser.walletAddress ?: ""),
                    "isSystemMessage" to "false",
                    "tokenAmount" to "0",
                    "receiverMessageId" to "0",
                    "mucname" to (room.name ?: ""),
                    "photoURL" to (currentUser.profileImage ?: ""),
                    "isMediafile" to "true",
                    "createdAt" to uploadResult.createdAt,
                    "expiresAt" to (uploadResult.expiresAt ?: ""),
                    "fileName" to uploadResult.filename,
                    "isVisible" to (uploadResult.isVisible?.toString() ?: "true"),
                    "location" to uploadResult.location,
                    "locationPreview" to (uploadResult.locationPreview ?: ""),
                    "mimetype" to uploadResult.mimetype,
                    "originalName" to uploadResult.originalName,
                    "ownerKey" to (uploadResult.ownerKey ?: ""),
                    "size" to uploadResult.size,
                    "duration" to (uploadResult.duration ?: ""),
                    "updatedAt" to (uploadResult.updatedAt ?: ""),
                    "userId" to (uploadResult.userId ?: currentUser.id),
                    "waveForm" to (uploadResult.waveForm ?: ""),
                    "attachmentId" to (uploadResult.attachmentId ?: ""),
                    "isReply" to "false",
                    "showInChannel" to "true",
                    "mainMessage" to "",
                    "roomJid" to room.jid,
                    "push" to "true"
                )
                
                // Send media message via XMPP
                val success = xmppClient?.sendMediaMessage(room.jid, mediaData, messageId) ?: false
                
                if (success) {
                    android.util.Log.d("ChatRoomViewModel", "✅ Media message sent successfully")
                    // Update optimistic message with real data
                    val finalMessage = optimisticMessage.copy(
                        location = uploadResult.location,
                        locationPreview = uploadResult.locationPreview,
                        pending = false
                    )
                    MessageStore.updateMessage(room.jid, finalMessage)
                } else {
                    android.util.Log.e("ChatRoomViewModel", "❌ Failed to send media message via XMPP")
                    // TODO: Mark message as failed
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error sending media", e)
                // TODO: Mark message as failed
            }
        }
    }
    
    /**
     * Edit message
     */
    fun editMessage(messageId: String, newText: String) {
        viewModelScope.launch {
            try {
                xmppClient?.editMessage(room.jid, messageId, newText)
                // MessageStore will be updated via delegate callback
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error editing message", e)
            }
        }
    }
    
    /**
     * Delete message
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                xmppClient?.deleteMessage(room.jid, messageId)
                // MessageStore will be updated via delegate callback
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error deleting message", e)
            }
        }
    }
    
    /**
     * Send reaction
     */
    fun sendReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            try {
                val currentUser = UserStore.currentUser.value
                if (currentUser == null) {
                    android.util.Log.e("ChatRoomViewModel", "Cannot send reaction: user is null")
                    return@launch
                }
                
                // Get current reactions for this message
                val currentMessages = _messages.value
                val message = currentMessages.firstOrNull { it.id == messageId }
                val currentReactions = message?.reaction?.get(currentUser.xmppUsername?.split("@")?.firstOrNull() ?: currentUser.id)?.emoji ?: emptyList()
                
                // Toggle reaction: if already present, remove it; otherwise add it
                val newReactions = if (currentReactions.contains(emoji)) {
                    currentReactions.filter { it != emoji }
                } else {
                    currentReactions + emoji
                }
                
                xmppClient?.sendMessageReaction(room.jid, messageId, newReactions)
                // MessageStore will be updated via delegate callback
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error sending reaction", e)
            }
        }
    }
}
