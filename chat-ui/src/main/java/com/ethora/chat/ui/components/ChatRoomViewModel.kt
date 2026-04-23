package com.ethora.chat.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatEvent
import com.ethora.chat.core.config.MessageType
import com.ethora.chat.core.config.OutgoingSendInput
import com.ethora.chat.core.config.SendDecision
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.store.ChatEventDispatcher
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.ChatStore
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
import android.graphics.BitmapFactory

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

    private val _scrollRestoreAnchor = MutableStateFlow<String?>(null)
    val scrollRestoreAnchor: StateFlow<String?> = _scrollRestoreAnchor.asStateFlow()

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
                    // Sort ascending: oldest at index 0 for normal LazyColumn
                    roomMessages.sortedBy { it.timestamp ?: it.date.time }
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
            viewModelScope.launch {
                // Show loader until we've checked persistence (avoids flash of "No messages yet")
                RoomStore.setRoomLoading(room.jid, true)
                val persistedMessages = MessageStore.loadMessagesFromPersistence(room.jid)
                if (persistedMessages.isNotEmpty()) {
                    MessageStore.setMessagesForRoom(room.jid, persistedMessages)
                    _messages.value = persistedMessages.sortedBy { it.timestamp ?: it.date.time }
                    RoomStore.setRoomLoading(room.jid, false)
                    android.util.Log.d("ChatRoomViewModel", "Loaded ${persistedMessages.size} messages from persistence")
                    // Critical: even when cache hit, refresh newest MAM in background so any messages
                    // that arrived while the app was closed are merged in. Fixes "latest messages disappear
                    // after reload" where the cached view is shown but server has newer messages.
                    refreshNewestMessages()
                    return@launch
                }
                // No persistence - wait for global loader or load from XMPP
                val shouldWaitForGlobalLoader =
                    xmppClient != null &&
                    RoomStore.rooms.value.isNotEmpty() &&
                    (MessageLoader.isSyncInProgress() || !MessageLoader.isSynced())

                if (shouldWaitForGlobalLoader) {
                    android.util.Log.d("ChatRoomViewModel", "⏳ Waiting for global history sync before per-room load: ${room.jid}")
                    kotlinx.coroutines.delay(400)
                    val afterWaitMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                    if (afterWaitMessages.isNotEmpty()) {
                        _messages.value = afterWaitMessages.sortedBy { it.timestamp ?: it.date.time }
                        android.util.Log.d("ChatRoomViewModel", "Global loader filled messages (${afterWaitMessages.size})")
                        RoomStore.setRoomLoading(room.jid, false)
                        refreshNewestMessages()
                        return@launch
                    }
                }

                // No messages in store or persistence, load from XMPP
                loadMessages()
            }
        } else {
            // Messages already loaded globally, just use them (no loader)
            val sorted = storedMessages.sortedBy { it.timestamp ?: it.date.time }
            _messages.value = sorted
            android.util.Log.d("ChatRoomViewModel", "Using pre-loaded messages (${sorted.size} messages, sorted oldest-first)")
            // Refresh newest MAM to catch anything missed while on another screen.
            viewModelScope.launch { refreshNewestMessages() }
        }
    }

    /**
     * Fetch the newest page of MAM in background and merge with the current store.
     * Safe to call whenever the user (re-)enters a room; dedup in MessageStore.addMessages
     * prevents duplicates. Fixes cache-only re-entry where server has newer messages.
     */
    private suspend fun refreshNewestMessages() {
        val client = xmppClient ?: return
        try {
            if (!client.isFullyConnected()) {
                if (!client.ensureConnected(5_000)) return
            }
            client.promoteRoomHistory(room.jid)
            try { client.sendPresenceInRoom(room.jid) } catch (_: Exception) {}

            val latest = client.getHistory(room.jid, max = 50, beforeMessageId = null)
            if (latest.isEmpty()) return

            // Web parity: `useRoomInitialization.getDefaultHistory` calls
            // `setRoomMessages` (= mergeRoomMessages reducer). ALWAYS a merge —
            // never a replace — so re-entering a room doesn't wipe cached
            // older messages the user had already scrolled through. Any gap
            // between cache and the latest page stays until the user scrolls
            // up (which fills it via `<before>anchor</before>` pagination).
            MessageStore.addMessages(room.jid, latest)
            android.util.Log.d(
                "ChatRoomViewModel",
                "refreshNewest: merged ${latest.size} newest msgs into ${room.jid}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChatRoomViewModel", "refreshNewestMessages failed for ${room.jid}", e)
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
                ChatEventDispatcher.emit(
                    ChatEvent.MessageFailed(
                        roomJid = room.jid,
                        messageType = MessageType.TEXT,
                        text = text,
                        error = IllegalStateException("User is null")
                    )
                )
                return@launch
            }
            
            if (text.isBlank()) {
                android.util.Log.w("ChatRoomViewModel", "Cannot send empty message")
                return@launch
            }

            val config = ChatStore.getConfig()
            val interceptionInput = OutgoingSendInput(
                roomJid = room.jid,
                messageType = MessageType.TEXT,
                text = text,
                parentMessageId = parentMessageId
            )
            val sendDecision = config?.onBeforeSend?.invoke(interceptionInput)
            if (sendDecision is SendDecision.Cancel) return@launch

            val effectiveInput = when (sendDecision) {
                is SendDecision.Proceed -> sendDecision.input
                else -> interceptionInput
            }
            val finalText = effectiveInput.text ?: text
            val finalParentMessageId = effectiveInput.parentMessageId ?: parentMessageId
            if (finalText.isBlank()) return@launch
            
            android.util.Log.d("ChatRoomViewModel", "Attempting to send message: '$text' to room: ${room.jid}")
            android.util.Log.d("ChatRoomViewModel", "  User: ${currentUser.firstName} ${currentUser.lastName}, xmppUsername: ${currentUser.xmppUsername}")
            android.util.Log.d("ChatRoomViewModel", "  XMPP Client: ${xmppClient != null}, isFullyConnected: ${xmppClient?.isFullyConnected()}")
            
            xmppClient?.let { client ->
                if (!client.isFullyConnected()) {
                    android.util.Log.d("ChatRoomViewModel", "  XMPP not ready yet, waiting up to 15s for connection...")
                    if (!client.ensureConnected(15_000)) {
                        android.util.Log.e("ChatRoomViewModel", "Cannot send message: XMPP not connected after 15s")
                        ChatEventDispatcher.emit(
                            ChatEvent.MessageFailed(
                                roomJid = room.jid,
                                messageType = MessageType.TEXT,
                                text = finalText,
                                error = IllegalStateException("XMPP not connected")
                            )
                        )
                        return@launch
                    }
                }
            }
            
            val messageId = xmppClient?.sendMessage(
                roomJID = room.jid,
                messageBody = finalText,
                firstName = currentUser.firstName,
                lastName = currentUser.lastName,
                photo = currentUser.profileImage,
                walletAddress = currentUser.walletAddress,
                isReply = finalParentMessageId != null,
                mainMessage = finalParentMessageId
            )
            
            if (messageId != null) {
                // Force the optimistic message's timestamp strictly AFTER the
                // most recent known message. Without this, server messages
                // whose archive stanza-id resolves to a ts slightly ahead of
                // our wall clock (clock drift / µs-precision) land AFTER the
                // optimistic in the sort → user sees their freshly-sent
                // message appearing in the MIDDLE of the list. Matches web's
                // useSendMessage, which sets messageTimestampMs = Date.now()
                // and benefits from the same server clock behaviour.
                val lastKnownTs = MessageStore.lastKnownTimestamp(room.jid)
                val optimisticTs = maxOf(System.currentTimeMillis(), lastKnownTs + 1)
                val optimisticMessage = Message(
                    id = messageId,
                    body = finalText,
                    user = currentUser,
                    date = java.util.Date(optimisticTs),
                    timestamp = optimisticTs,
                    roomJid = room.jid,
                    pending = true,
                    xmppId = messageId, // Set xmppId for bidirectional matching
                    xmppFrom = "${room.jid}/${currentUser.id}",
                    isReply = finalParentMessageId != null,
                    mainMessage = finalParentMessageId
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                android.util.Log.d("ChatRoomViewModel", "Created optimistic message with pending=true, ID: $messageId, xmppId: $messageId")
                // Web parity (useSendMessage.tsx L316-317): fast-ack immediately,
                // then start the 5-second catchup poll.
                launch { triggerFastAckFetch() }
                schedulePendingFallback(messageId)
                ChatEventDispatcher.emit(
                    ChatEvent.MessageSent(
                        roomJid = room.jid,
                        messageId = messageId,
                        messageType = MessageType.TEXT,
                        user = currentUser,
                        text = finalText
                    )
                )
            } else {
                android.util.Log.e("ChatRoomViewModel", "Failed to send message - sendMessage returned null")
                ChatEventDispatcher.emit(
                    ChatEvent.MessageFailed(
                        roomJid = room.jid,
                        messageType = MessageType.TEXT,
                        text = finalText,
                        error = IllegalStateException("sendMessage returned null")
                    )
                )
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
                            kotlinx.coroutines.delay(80)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ChatRoomViewModel", "Failed to send presence to ${room.jid}", e)
                    }
                    
                    val initialLoadMax = 50
                    val initialHistory = client.getHistory(room.jid, max = initialLoadMax, beforeMessageId = null)
                    if (initialHistory.isNotEmpty()) {
                        MessageStore.addMessages(room.jid, initialHistory)
                    }

                    val undefinedTextCount = initialHistory.count {
                        it.body == null || it.body.isEmpty() || it.body == "undefined"
                    }

                    if (undefinedTextCount > 0 && initialHistory.isNotEmpty()) {
                        val anchorMessageId = initialHistory.firstOrNull()?.id
                        if (anchorMessageId != null) {
                            val additionalHistory = client.getHistory(
                                room.jid,
                                max = 20 + undefinedTextCount,
                                beforeMessageId = anchorMessageId
                            )
                            if (additionalHistory.isNotEmpty()) {
                                MessageStore.addMessages(room.jid, additionalHistory)
                            }
                        }
                    }

                    // hasMoreMessages should only be set `false` by an authoritative
                    // `<fin complete="true">` signal — NOT by a short page (server
                    // may return < max but still have older history). This matches
                    // web which only trusts historyComplete from the fin IQ.
                    val updatedRoom = RoomStore.getRoomByJid(room.jid)
                    val historyComplete = updatedRoom?.historyComplete == true
                    _hasMoreMessages.value = !historyComplete
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
     * Matches web: loadMoreMessages in ChatRoom.tsx exactly
     * 
     * React logic:
     * - Check: !isLoadingMore && !roomsList?.[chatJID]?.historyComplete
     * - Get lastMsgId: if idOfMessageBefore provided, use it; else use messages[length-2].id (skip delimiter-new)
     * - Call: client?.getHistoryStanza(chatJID, max, lastMsgId)
     */
    fun loadMoreMessages(idOfMessageBefore: String? = null) {
        // historyComplete from initial MAM query means "that query finished", NOT "no older messages exist".
        // We must always attempt loadMore when user scrolls up; empty response will set hasMoreMessages=false.
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
                val oldestMessage = messagesWithoutDelimiter.firstOrNull()
                if (oldestMessage == null) {
                    android.util.Log.d("ChatRoomViewModel", "No valid message ID found for pagination")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }
                
                _scrollRestoreAnchor.value = oldestMessage.id

                // Use OLDEST SERVER message for beforeId - optimistic ids are not in server archive.
                val anchorMsg = messagesWithoutDelimiter.firstOrNull { msg ->
                    !msg.id.startsWith("send-text-message-") && !msg.id.startsWith("pending-")
                } ?: oldestMessage

                // Match web's `loadMoreMessages(chatJID, 30, Number(firstMessageId))`:
                // the server expects a NUMERIC `<before>` value. If the message id
                // has a 10+ digit numeric chunk (Ethora's archive ids embed a ms
                // timestamp — e.g. "1704067200000" or "send-media-message:…-1704067200000"),
                // use that; otherwise fall through to the message timestamp.
                // Sending a non-numeric id was making the server drop the `<before>`
                // constraint and return the FIRST page of the archive, producing
                // the classic "skip middle, jump to 1-9" pagination bug.
                val idStr = anchorMsg.id
                val numericFromId = Regex("\\d{10,}").find(idStr)?.value
                val beforeId = numericFromId
                    ?: anchorMsg.timestamp?.takeIf { it > 0 }?.toString()
                    ?: anchorMsg.date.time.toString()

                android.util.Log.d(
                    "ChatRoomViewModel",
                    "Triggering loadMoreMessages room=${room.jid} anchorId=${anchorMsg.id} beforeId=$beforeId"
                )
                
                xmppClient?.let { client ->
                    val history = client.getHistory(room.jid, max = 30, beforeMessageId = beforeId)
                    android.util.Log.d("ChatRoomViewModel", "  Received ${history.size} older messages")
                    
                    if (history.isNotEmpty()) {
                        MessageStore.addMessages(room.jid, history)
                        android.util.Log.d("ChatRoomViewModel", "   Added ${history.size} older messages to store")
                        kotlinx.coroutines.delay(50)
                        val updated = (MessageStore.messages.value[room.jid] ?: emptyList())
                            .sortedBy { it.timestamp ?: it.date.time }
                        _messages.value = updated
                        android.util.Log.d("ChatRoomViewModel", "   UI updated: ${updated.size} total messages")
                    }

                    if (history.isEmpty()) {
                        android.util.Log.d("ChatRoomViewModel", "   No more messages available (empty response)")
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

    fun clearScrollRestoreAnchor() {
        _scrollRestoreAnchor.value = null
    }
    
    /**
     * Send media with retry mechanism and error handling
     */
    fun sendMedia(file: File, mimeType: String, retryCount: Int = 0) {
        viewModelScope.launch {
            try {
                val config = ChatStore.getConfig()
                val interceptionInput = OutgoingSendInput(
                    roomJid = room.jid,
                    messageType = MessageType.MEDIA,
                    fileName = file.name,
                    mimeType = mimeType
                )
                val sendDecision = config?.onBeforeSend?.invoke(interceptionInput)
                if (sendDecision is SendDecision.Cancel) return@launch
                val effectiveInput = when (sendDecision) {
                    is SendDecision.Proceed -> sendDecision.input
                    else -> interceptionInput
                }
                val effectiveMimeType = effectiveInput.mimeType ?: mimeType

                val currentUser = UserStore.currentUser.value
                // Fix token check: check both store token and user token
                val token = UserStore.token.value ?: currentUser?.token
                
                if (currentUser == null || token == null) {
                    android.util.Log.e("ChatRoomViewModel", "Cannot send media: user or token is null")
                    ChatEventDispatcher.emit(
                        ChatEvent.MessageFailed(
                            roomJid = room.jid,
                            messageType = MessageType.MEDIA,
                            error = IllegalStateException("User or token is null"),
                            metadata = mapOf("fileName" to file.name, "mimeType" to effectiveMimeType)
                        )
                    )
                    return@launch
                }
                
                // Validate file exists and is readable
                if (!file.exists() || !file.canRead()) {
                    android.util.Log.e("ChatRoomViewModel", "File does not exist or cannot be read: ${file.absolutePath}")
                    ChatEventDispatcher.emit(
                        ChatEvent.MediaUploadResult(
                            roomJid = room.jid,
                            fileName = file.name,
                            mimeType = effectiveMimeType,
                            success = false,
                            error = IllegalStateException("File does not exist or cannot be read")
                        )
                    )
                    return@launch
                }

                val preparedMedia = prepareMediaForUpload(file, effectiveMimeType)
                val uploadFile = preparedMedia.first
                val uploadMimeType = preparedMedia.second
                
                // Validate file size (max 50MB)
                val maxSizeBytes = 50 * 1024 * 1024L // 50MB
                val fileSize = uploadFile.length()
                if (fileSize > maxSizeBytes) {
                    android.util.Log.e("ChatRoomViewModel", "File too large: ${fileSize} bytes (max: $maxSizeBytes)")
                    ChatEventDispatcher.emit(
                        ChatEvent.MediaUploadResult(
                            roomJid = room.jid,
                            fileName = uploadFile.name,
                            mimeType = uploadMimeType,
                            success = false,
                            error = IllegalStateException("File too large")
                        )
                    )
                    return@launch
                }
                
                // Create optimistic message
                // Use timestamp-based ID that will be replaced by server echo
                val messageId = "send-media-message:${UUID.randomUUID()}"
                // Same max(now, last+1) trick as text send — keeps the freshly
                // sent media at the very bottom even if server clocks drift.
                val lastKnownTs = MessageStore.lastKnownTimestamp(room.jid)
                val optimisticTs = maxOf(System.currentTimeMillis(), lastKnownTs + 1)
                val optimisticMessage = Message(
                    id = messageId,
                    body = "media",
                    user = currentUser,
                    date = Date(optimisticTs),
                    timestamp = optimisticTs,
                    roomJid = room.jid,
                    pending = true,
                    isDeleted = false,
                    xmppId = messageId, // Set xmppId for bidirectional matching
                    xmppFrom = "${room.jid}/${currentUser.id}",
                    isSystemMessage = "false",
                    isMediafile = "true",
                    fileName = uploadFile.name,
                    // Use the local file URI so the image/video appears in the bubble
                    // immediately while the upload runs. Coil handles `file://` URIs.
                    // When the server echo returns, both `location` and `locationPreview`
                    // get replaced with the real CDN URL in the deep-merge.
                    location = "file://${uploadFile.absolutePath}",
                    locationPreview = if (uploadMimeType.startsWith("image/")) "file://${uploadFile.absolutePath}" else "",
                    mimetype = uploadMimeType,
                    originalName = uploadFile.name,
                    size = uploadFile.length().toString()
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                android.util.Log.d("ChatRoomViewModel", "Created optimistic media message with pending=true, ID: $messageId")
                schedulePendingFallback(messageId)
                
                // Upload file with retry
                android.util.Log.d("ChatRoomViewModel", "Uploading file: ${uploadFile.name} (${uploadMimeType}), attempt ${retryCount + 1}")
                var uploadResult: com.ethora.chat.core.networking.FileUploadResult? = null
                
                val baseUrl = ChatStore.getEffectiveBaseUrl()
                try {
                    uploadResult = AuthAPIHelper.uploadFile(uploadFile, uploadMimeType, token, baseUrl)
                } catch (e: Exception) {
                    android.util.Log.e("ChatRoomViewModel", "File upload exception", e)
                }
                
                if (uploadResult == null) {
                    if (retryCount < 2) {
                        android.util.Log.w("ChatRoomViewModel", "File upload failed, retrying... attempt ${retryCount + 2}")
                        kotlinx.coroutines.delay(1200)
                        sendMedia(file, effectiveMimeType, retryCount + 1)
                        MessageStore.removeMessage(room.jid, messageId)
                        return@launch
                    }
                    android.util.Log.e("ChatRoomViewModel", "File upload failed after retries")
                    MessageStore.removeMessage(room.jid, messageId)
                    ChatEventDispatcher.emit(
                        ChatEvent.MediaUploadResult(
                            roomJid = room.jid,
                            fileName = uploadFile.name,
                            mimeType = uploadMimeType,
                            success = false,
                            error = IllegalStateException("Upload failed after retries")
                        )
                    )
                    return@launch
                }

                ChatEventDispatcher.emit(
                    ChatEvent.MediaUploadResult(
                        roomJid = room.jid,
                        fileName = uploadResult.filename,
                        mimeType = uploadResult.mimetype,
                        success = true
                    )
                )
                
                android.util.Log.d("ChatRoomViewModel", "File uploaded: ${uploadResult.location}")
                
                // Prepare media data for XMPP
                val xmppHost = ChatStore.getEffectiveXmppSettings().host
                val senderJID = currentUser.xmppUsername?.let { "$it@$xmppHost" } ?: ""
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
                    // Web parity: fast-ack immediately after successful XMPP send
                    // so pending flips as soon as server echo hits MAM.
                    launch { triggerFastAckFetch() }
                    android.util.Log.d("ChatRoomViewModel", "Updated optimistic message with upload data (pending=true, waiting for server echo to match by location: ${uploadResult.location})")
                    ChatEventDispatcher.emit(
                        ChatEvent.MessageSent(
                            roomJid = room.jid,
                            messageId = messageId,
                            messageType = MessageType.MEDIA,
                            user = currentUser,
                            metadata = mapOf(
                                "location" to uploadResult.location,
                                "mimetype" to uploadResult.mimetype
                            )
                        )
                    )
                } else {
                    android.util.Log.e("ChatRoomViewModel", "Failed to send media message via XMPP. xmppClient=${xmppClient != null}, isFullyConnected=${xmppClient?.isFullyConnected()} (check XMPPClient/XMPPWebSocket logs for cause)")
                    // Remove failed message
                    MessageStore.removeMessage(room.jid, messageId)
                    ChatEventDispatcher.emit(
                        ChatEvent.MessageFailed(
                            roomJid = room.jid,
                            messageType = MessageType.MEDIA,
                            error = IllegalStateException("Failed to send media message via XMPP"),
                            metadata = mapOf("fileName" to uploadFile.name, "mimeType" to uploadMimeType)
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error sending media", e)
                // Message will remain in pending state, will be cleaned up later
                ChatEventDispatcher.emit(
                    ChatEvent.MessageFailed(
                        roomJid = room.jid,
                        messageType = MessageType.MEDIA,
                        error = e,
                        metadata = mapOf("fileName" to file.name, "mimeType" to mimeType)
                    )
                )
            }
        }
    }

    private suspend fun prepareMediaForUpload(file: File, mimeType: String): Pair<File, String> {
        val isHeic = mimeType.equals("image/heic", ignoreCase = true) ||
            mimeType.equals("image/heif", ignoreCase = true)
        if (!isHeic) return file to mimeType

        return withContext(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap == null) {
                    android.util.Log.w("ChatRoomViewModel", "Failed to decode HEIC/HEIF, fallback to original file")
                    return@withContext file to mimeType
                }
                val converted = File.createTempFile(
                    "media_jpeg_${System.currentTimeMillis()}",
                    ".jpg",
                    file.parentFile
                )
                FileOutputStream(converted).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                converted to "image/jpeg"
            } catch (e: Exception) {
                android.util.Log.w("ChatRoomViewModel", "HEIC conversion failed, fallback to original file", e)
                file to mimeType
            }
        }
    }

    /**
     * Port of web's `triggerFastAckFetch` — throttled (600 ms per room) presence
     * + short MAM fetch immediately after a send. Pulls the server echo fast if
     * the real-time stanza route missed it. Web: useSendMessage.tsx L184-203.
     */
    private suspend fun triggerFastAckFetch() {
        val client = xmppClient ?: return
        if (!client.isFullyConnected()) return
        val now = System.currentTimeMillis()
        val lastAt = fastAckLastByRoom[room.jid] ?: 0L
        if (now - lastAt < 600L) return
        fastAckLastByRoom[room.jid] = now
        try {
            client.promoteRoomHistory(room.jid)
            try { client.sendPresenceInRoom(room.jid) } catch (_: Exception) {}
            val latest = client.getHistory(room.jid, max = 10, beforeMessageId = null)
            if (latest.isNotEmpty()) {
                MessageStore.addMessages(room.jid, latest)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChatRoomViewModel", "triggerFastAckFetch failed", e)
        }
    }

    /**
     * Port of web's `scheduleAckCatchup` — useSendMessage.tsx L205-236.
     * After a send, aggressively polls MAM (presence + 20-message fetch) until
     * either the message transitions out of `pending` state or 5 seconds elapse.
     *   Initial delay: 150 ms
     *   Poll interval: 700 ms
     *   Window:        5000 ms (≈ 7 polls)
     *
     * Web's final safety net (forcibly clearing `pending`) is handled for us by
     * `MessageStore.schedulePendingTimeout` which fires at 6 s regardless of
     * ViewModel lifecycle.
     */
    private fun schedulePendingFallback(messageId: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 5_000) {
                if (!isStillPending(messageId)) return@launch
                val client = xmppClient
                if (client != null && client.isFullyConnected()) {
                    try {
                        client.promoteRoomHistory(room.jid)
                        try { client.sendPresenceInRoom(room.jid) } catch (_: Exception) {}
                        val latest = client.getHistory(room.jid, max = 20, beforeMessageId = null)
                        if (latest.isNotEmpty()) {
                            MessageStore.addMessages(room.jid, latest)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("ChatRoomViewModel", "ackCatchup poll failed", e)
                    }
                }
                if (!isStillPending(messageId)) return@launch
                kotlinx.coroutines.delay(700)
            }
        }
    }

    private fun isStillPending(messageId: String): Boolean {
        return MessageStore.getMessagesForRoom(room.jid)
            .any { it.id == messageId && it.pending == true }
    }

    companion object {
        // Room-keyed "last fast-ack" timestamps — shared across ViewModel
        // instances so switching chats doesn't reset the 600 ms throttle.
        private val fastAckLastByRoom = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
    
    /**
     * Edit message.
     * Optimistic update so UI changes immediately; server echo will confirm via onMessageEdited.
     */
    fun editMessage(messageId: String, newText: String) {
        MessageStore.editMessage(room.jid, messageId, newText)
        ChatEventDispatcher.emit(
            ChatEvent.MessageEdited(
                roomJid = room.jid,
                messageId = messageId,
                newText = newText
            )
        )
        viewModelScope.launch {
            try {
                xmppClient?.editMessage(room.jid, messageId, newText)
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error editing message", e)
            }
        }
    }

    /**
     * Delete message.
     * Optimistic update so UI updates immediately; server echo will confirm via parseAndHandleDeleteMessage.
     */
    fun deleteMessage(messageId: String) {
        MessageStore.markMessageAsDeleted(room.jid, messageId)
        ChatEventDispatcher.emit(
            ChatEvent.MessageDeleted(
                roomJid = room.jid,
                messageId = messageId
            )
        )
        viewModelScope.launch {
            try {
                xmppClient?.deleteMessage(room.jid, messageId)
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
                ChatEventDispatcher.emit(
                    ChatEvent.ReactionSent(
                        roomJid = room.jid,
                        messageId = messageId,
                        emoji = emoji
                    )
                )
                // MessageStore will be updated via delegate callback
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error sending reaction", e)
            }
        }
    }
}
