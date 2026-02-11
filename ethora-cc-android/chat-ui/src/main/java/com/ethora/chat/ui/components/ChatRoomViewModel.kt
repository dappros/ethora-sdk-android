package com.ethora.chat.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ViewModel for chat room
 */
class ChatRoomViewModel(
    private val room: Room,
    private val xmppClient: XMPPClient?
) : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Loading state comes from store
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
        // Observe messages from store
        viewModelScope.launch {
            MessageStore.messages
                .map { 
                    val roomMessages = it[room.jid] ?: emptyList()
                    // Re-sort descending: newest at index 0 for LazyColumn(reverseLayout=true)
                    val sorted = roomMessages.sortedBy { it.timestamp ?: it.date.time }
                    android.util.Log.d("ChatRoomViewModel", "Store updated, messages for ${room.jid}: ${roomMessages.size} (displayed: ${sorted.size})")
                    sorted
                }
                .distinctUntilChanged()
                .collect { 
                    _messages.value = it
                }
        }
        
        // Observe composing state from RoomStore
        viewModelScope.launch {
            RoomStore.rooms
                .map { rooms ->
                    val currentRoom = rooms.firstOrNull { it.jid == room.jid }
                    currentRoom?.composingList ?: emptyList()
                }
                .distinctUntilChanged()
                .collect { composingList ->
                    _composingUsers.value = composingList
                    android.util.Log.d("ChatRoomViewModel", "Composing users updated: $composingList")
                }
        }
        
        // Check if messages exist in store - only load if they don't
        val storedMessages = MessageStore.messages.value[room.jid] ?: emptyList()
        if (storedMessages.isEmpty()) {
            // Try loading from persistence first
            viewModelScope.launch {
                val persistedMessages = MessageStore.loadMessagesFromPersistence(room.jid)
                if (persistedMessages.isNotEmpty()) {
                    // Load from persistence
                    MessageStore.setMessagesForRoom(room.jid, persistedMessages)
                    _messages.value = persistedMessages.sortedBy { it.timestamp ?: it.date.time }
                    android.util.Log.d("ChatRoomViewModel", "Loaded ${persistedMessages.size} messages from persistence (sorted newest-first)")
                } else {
                    // Avoid racing the global MessageLoader (which runs shortly after rooms load).
                    // If global sync is in progress (or hasn't finished yet), wait briefly for it to populate the store,
                    // then fall back to per-room load if still empty.
                    val shouldWaitForGlobalLoader =
                        xmppClient != null &&
                        RoomStore.rooms.value.isNotEmpty() &&
                        (MessageLoader.isSyncInProgress() || !MessageLoader.isSynced())

                    if (shouldWaitForGlobalLoader) {
                        android.util.Log.d("ChatRoomViewModel", "⏳ Waiting for global history sync before per-room load: ${room.jid}")
                        kotlinx.coroutines.delay(1500)
                        val afterWaitMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                        if (afterWaitMessages.isNotEmpty()) {
                            _messages.value = afterWaitMessages.sortedBy { it.timestamp ?: it.date.time }
                            android.util.Log.d("ChatRoomViewModel", "Global loader filled messages (${afterWaitMessages.size}), skipping per-room load (sorted newest-first)")
                            return@launch
                        }
                    }

                    // No messages in store or persistence, load them
                    RoomStore.setRoomLoading(room.jid, true)
                    loadMessages()
                }
            }
        } else {
            // Messages already loaded globally, just use them (no loader)
            _messages.value = storedMessages
            android.util.Log.d("ChatRoomViewModel", "Using pre-loaded messages (${storedMessages.size} messages)")
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
                xmppClient?.sendTypingRequest(room.jid, fullName, true)
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
                xmppClient?.sendTypingRequest(room.jid, fullName, false)
            }
        }
    }

    /**
     * Send message
     */
    fun sendMessage(text: String, parentMessageId: String? = null) {
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
            
            android.util.Log.d("ChatRoomViewModel", "Attempting to send message: '$text' to room: ${room.jid}")
            android.util.Log.d("ChatRoomViewModel", "  User: ${currentUser.firstName} ${currentUser.lastName}, xmppUsername: ${currentUser.xmppUsername}")
            android.util.Log.d("ChatRoomViewModel", "  XMPP Client: ${xmppClient != null}, isFullyConnected: ${xmppClient?.isFullyConnected()}")
            
            val messageId = xmppClient?.sendMessage(
                roomJID = room.jid,
                messageBody = text,
                firstName = currentUser.firstName,
                lastName = currentUser.lastName,
                photo = currentUser.profileImage,
                walletAddress = currentUser.walletAddress,
                isReply = parentMessageId != null,
                mainMessage = parentMessageId
            )
            
            if (messageId != null) {
                // Create optimistic message with pending state
                // Set both id and xmppId to messageId for proper matching when server echo arrives
                val optimisticMessage = Message(
                    id = messageId,
                    body = text,
                    user = currentUser,
                    date = java.util.Date(),
                    roomJid = room.jid,
                    pending = true,
                    xmppId = messageId, // Set xmppId for bidirectional matching
                    xmppFrom = "${room.jid}/${currentUser.id}",
                    isReply = parentMessageId != null,
                    mainMessage = parentMessageId
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                android.util.Log.d("ChatRoomViewModel", "Created optimistic message with pending=true, ID: $messageId, xmppId: $messageId")
            } else {
                android.util.Log.e("ChatRoomViewModel", "Failed to send message - sendMessage returned null")
            }
        }
    }

    /**
     * Send media
     */
    /**
     * Send media
     */
    fun sendMedia(data: ByteArray, mimeType: String) {
        viewModelScope.launch {
            try {
                // Create temp file
                val file = withContext(Dispatchers.IO) {
                    val tempFile = File.createTempFile("upload_${System.currentTimeMillis()}", ".tmp")
                    val fos = FileOutputStream(tempFile)
                    fos.write(data)
                    fos.close()
                    tempFile
                }
                
                // Delegate to sendMedia(File) with default retry count
                sendMedia(file, mimeType)
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error processing media data", e)
            }
        }
    }

    /**
     * Load messages (initial load)
     * Only called when messages don't exist in store
     * Matches web: useRoomInitialization hook exactly
     * 
     * Web logic:
     * 1. Load 30 messages initially
     * 2. Check for undefined/empty text messages (countUndefinedText)
     * 3. If undefined texts found, load additional 20 + countUndefinedText messages before the first message ID
     */
    fun loadMessages() {
        viewModelScope.launch {
            try {
                RoomStore.setRoomLoading(room.jid, true)
                
                xmppClient?.let { client ->
                    try {
                        // Only send presence if fully connected
                        if (client.isFullyConnected()) {
                            client.sendPresenceInRoom(room.jid)
                            kotlinx.coroutines.delay(200)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ChatRoomViewModel", "Failed to send presence to ${room.jid}", e)
                    }
                    
                    val initialHistory = client.getHistory(room.jid, max = 30, beforeMessageId = null)
                    
                    val undefinedTextCount = initialHistory.count { 
                        it.body == null || it.body.isEmpty() || it.body == "undefined"
                    }
                    
                    if (undefinedTextCount > 0 && initialHistory.isNotEmpty()) {
                        val oldestMessageId = initialHistory.lastOrNull()?.id
                        
                        if (oldestMessageId != null) {
                            val additionalHistory = client.getHistory(
                                room.jid,
                                max = 20 + undefinedTextCount,
                                beforeMessageId = oldestMessageId
                            )
                            
                            val allMessages = (additionalHistory + initialHistory).distinctBy { it.id }
                            MessageStore.addMessages(room.jid, allMessages)
                            
                            val updatedRoom = RoomStore.getRoomByJid(room.jid)
                            val historyComplete = updatedRoom?.historyComplete == true
                            _hasMoreMessages.value = !historyComplete && allMessages.size >= 30
                            
                            if (historyComplete) {
                                _hasMoreMessages.value = false
                            }
                        } else {
                            MessageStore.addMessages(room.jid, initialHistory)
                            
                            val updatedRoom = RoomStore.getRoomByJid(room.jid)
                            val historyComplete = updatedRoom?.historyComplete == true
                            _hasMoreMessages.value = !historyComplete && initialHistory.size >= 30
                            
                            if (historyComplete) {
                                _hasMoreMessages.value = false
                            }
                        }
                    } else {
                        if (initialHistory.isNotEmpty()) {
                            MessageStore.addMessages(room.jid, initialHistory)
                            
                            val updatedRoom = RoomStore.getRoomByJid(room.jid)
                            val historyComplete = updatedRoom?.historyComplete == true
                            _hasMoreMessages.value = !historyComplete && initialHistory.size >= 30
                            
                            if (historyComplete) {
                                _hasMoreMessages.value = false
                            }
                        } else {
                            _hasMoreMessages.value = false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error loading messages", e)
            } finally {
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
                    // Pass null to get newest messages (RSM <before/>)
                    val history = client.getHistory(room.jid, max = 30, beforeMessageId = null)
                    if (history.isNotEmpty()) {
                        // Add messages - this will automatically update last message
                        MessageStore.addMessages(room.jid, history)
                        _hasMoreMessages.value = history.size >= 30
                        android.util.Log.d("ChatRoomViewModel", "   Refreshed ${history.size} messages in background")
                    }
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled - don't log as error
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error refreshing messages", e)
            }
        }
    }
    
    /**
     * Load more messages (pagination) when scrolling to top
     * In reverse layout, "top" means oldest messages (first in the list)
     * Matches web: loadMoreMessages in ChatRoom.tsx
     */
    fun loadMoreMessages() {
        // historyComplete from initial MAM means "that query finished", NOT "no older messages exist".
        // Always attempt loadMore when user scrolls up; empty response sets hasMoreMessages=false.
        if (isLoadingMoreInProgress) {
            android.util.Log.d("ChatRoomViewModel", "Skipping loadMore: already in progress")
            return
        }
        
        viewModelScope.launch {
            isLoadingMoreInProgress = true
            _isLoadingMore.value = true
            try {
                val currentMessages = _messages.value
                if (currentMessages.isEmpty()) {
                    android.util.Log.d("ChatRoomViewModel", "No messages to paginate from")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }
                
                val messagesWithoutDelimiter = currentMessages.filter { it.id != "delimiter-new" }
                val oldestMessage = messagesWithoutDelimiter.lastOrNull()
                if (oldestMessage == null) {
                    android.util.Log.d("ChatRoomViewModel", "No valid oldest message found for pagination")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }

                val anchorMsg = messagesWithoutDelimiter.lastOrNull { msg ->
                    !msg.id.startsWith("send-text-message-") && !msg.id.startsWith("pending-")
                } ?: oldestMessage

                val idStr = anchorMsg.id
                val isOptimistic = idStr.startsWith("send-text-message-") || idStr.startsWith("pending-")
                // Critical: for server messages use archive id as-is (matches web MAM pagination semantics).
                // Numeric/timestamp fallback only for optimistic local messages not present in archive.
                val beforeMessageId = if (!isOptimistic && idStr.isNotBlank()) {
                    idStr
                } else {
                    anchorMsg.timestamp?.toString() ?: anchorMsg.date.time.toString()
                }
                
                android.util.Log.d("ChatRoomViewModel", "🚀 Triggering loadMoreMessages for ${room.jid} beforeId=$beforeMessageId (anchorId=${anchorMsg.id})")
                
                xmppClient?.let { client ->
                    val history = client.getHistory(room.jid, max = 30, beforeMessageId = beforeMessageId)
                    android.util.Log.d("ChatRoomViewModel", "  Received ${history.size} older messages")
                    
                    if (history.isNotEmpty()) {
                        // Add to message store - these will be older messages
                        MessageStore.addMessages(room.jid, history)
                        android.util.Log.d("ChatRoomViewModel", "   Added ${history.size} older messages to store")
                        
                        // Update hasMoreMessages - if we got less than max, we've reached the end
                        val hasMore = history.size >= 30
                        _hasMoreMessages.value = hasMore
                        
                        // Check if history is complete (from room)
                        val updatedRoom = RoomStore.getRoomByJid(room.jid)
                        if (updatedRoom?.historyComplete == true) {
                            _hasMoreMessages.value = false
                            android.util.Log.d("ChatRoomViewModel", "   History is complete, no more messages")
                        }
                        
                        // Force UI update to show new messages
                        kotlinx.coroutines.delay(50)
                        val updatedMessages = (MessageStore.messages.value[room.jid] ?: emptyList())
                            .sortedBy { it.timestamp ?: it.date.time }
                        _messages.value = updatedMessages
                        android.util.Log.d("ChatRoomViewModel", "   Updated UI with ${updatedMessages.size} total messages")
                        
                        if (!hasMore) {
                            android.util.Log.d("ChatRoomViewModel", "   Reached end of history (got ${history.size} < 30)")
                        }
                    } else {
                        android.util.Log.d("ChatRoomViewModel", "   No more messages available")
                        _hasMoreMessages.value = false
                    }
                } ?: run {
                    android.util.Log.w("ChatRoomViewModel", "   XMPP client is null")
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled - don't log as error
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error loading more messages", e)
            } finally {
                isLoadingMoreInProgress = false
                _isLoadingMore.value = false
                android.util.Log.d("ChatRoomViewModel", "  Finished loading more, isLoadingMore: false")
            }
        }
    }
    
    /**
     * Send media with retry mechanism and error handling
     */
    fun sendMedia(file: File, mimeType: String, retryCount: Int = 0) {
        viewModelScope.launch {
            try {
                val currentUser = UserStore.currentUser.value
                // Fix token check: check both store token and user token
                val token = UserStore.token.value ?: currentUser?.token
                
                if (currentUser == null || token == null) {
                    android.util.Log.e("ChatRoomViewModel", "Cannot send media: user or token is null")
                    return@launch
                }
                
                // Validate file exists and is readable
                if (!file.exists() || !file.canRead()) {
                    android.util.Log.e("ChatRoomViewModel", "File does not exist or cannot be read: ${file.absolutePath}")
                    return@launch
                }
                
                // Validate file size (max 50MB)
                val maxSizeBytes = 50 * 1024 * 1024L // 50MB
                val fileSize = file.length()
                if (fileSize > maxSizeBytes) {
                    android.util.Log.e("ChatRoomViewModel", "File too large: ${fileSize} bytes (max: $maxSizeBytes)")
                    return@launch
                }
                
                // Create optimistic message
                // Use timestamp-based ID that will be replaced by server echo
                val messageId = "send-media-message:${System.currentTimeMillis()}"
                val optimisticMessage = Message(
                    id = messageId,
                    body = "media",
                    user = currentUser,
                    date = Date(),
                    roomJid = room.jid,
                    pending = true,
                    isDeleted = false,
                    xmppId = messageId, // Set xmppId for bidirectional matching
                    xmppFrom = "${room.jid}/${currentUser.id}",
                    isSystemMessage = "false",
                    isMediafile = "true",
                    fileName = file.name,
                    location = "", // Will be set after upload
                    locationPreview = "",
                    mimetype = mimeType,
                    originalName = file.name,
                    size = file.length().toString()
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                android.util.Log.d("ChatRoomViewModel", "Created optimistic media message with pending=true, ID: $messageId")
                
                // Upload file with retry
                android.util.Log.d("ChatRoomViewModel", "Uploading file: ${file.name} (${mimeType}), attempt ${retryCount + 1}")
                var uploadResult: com.ethora.chat.core.networking.FileUploadResult? = null
                var uploadError: Exception? = null
                
                try {
                    uploadResult = AuthAPIHelper.uploadFile(file, mimeType, token)
                } catch (e: Exception) {
                    uploadError = e
                    android.util.Log.e("ChatRoomViewModel", "File upload exception", e)
                }
                
                if (uploadResult == null) {
                    android.util.Log.e("ChatRoomViewModel", "File upload failed")
                    MessageStore.removeMessage(room.jid, messageId)
                    return@launch
                }
                
                android.util.Log.d("ChatRoomViewModel", "File uploaded: ${uploadResult.location}")
                
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
                val success = try {
                    xmppClient?.sendMediaMessage(room.jid, mediaData, messageId) ?: false
                } catch (e: Exception) {
                    android.util.Log.e("ChatRoomViewModel", "Error sending media message via XMPP", e)
                    false
                }
                
                if (success) {
                    android.util.Log.d("ChatRoomViewModel", "Media message sent successfully via XMPP")
                    // Update optimistic message with upload data, but keep pending=true
                    // Server echo will clear pending state via pending reconciliation
                    // The server echo will match this message by location/fileName and clear pending
                    val updatedMessage = optimisticMessage.copy(
                        location = uploadResult.location,
                        locationPreview = uploadResult.locationPreview,
                        fileName = uploadResult.filename,
                        mimetype = uploadResult.mimetype,
                        size = uploadResult.size.toString(),
                        // Keep pending=true - server echo will clear it via updatePendingMessage
                        pending = true
                    )
                    MessageStore.updateMessage(room.jid, updatedMessage)
                    android.util.Log.d("ChatRoomViewModel", "Updated optimistic message with upload data (pending=true, waiting for server echo to match by location: ${uploadResult.location})")
                } else {
                    android.util.Log.e("ChatRoomViewModel", "Failed to send media message via XMPP")
                    // Remove failed message
                    MessageStore.removeMessage(room.jid, messageId)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error sending media", e)
                // Message will remain in pending state, will be cleaned up later
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
                android.util.Log.e("ChatRoomViewModel", "Error editing message", e)
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
                android.util.Log.e("ChatRoomViewModel", "Error deleting message", e)
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
                android.util.Log.e("ChatRoomViewModel", "Error sending reaction", e)
            }
        }
    }
}
