package com.ethora.chat.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatEvent
import com.ethora.chat.core.config.MessageType
import com.ethora.chat.core.config.OutgoingSendInput
import com.ethora.chat.core.config.SendDecision
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.MessageAttachment
import com.ethora.chat.core.models.MessageAttachments
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.store.ChatEventDispatcher
import com.ethora.chat.core.store.ChatConnectionStatus
import com.ethora.chat.core.store.ConnectionStore
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.PendingMediaFile
import com.ethora.chat.core.store.PendingMediaSend
import com.ethora.chat.core.store.PendingMediaSendQueue
import com.ethora.chat.core.store.PendingMediaSendStatus
import com.ethora.chat.core.store.PendingMediaUploadPayload
import com.ethora.chat.core.store.JumpRequestStore
import com.ethora.chat.core.store.PendingJump
import com.ethora.chat.core.store.PendingJumpPreview
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.threads.ThreadSupport
import com.ethora.chat.core.xmpp.JumpContent
import com.ethora.chat.core.xmpp.JumpWindow
import com.ethora.chat.core.xmpp.JumpWindowResult
import com.ethora.chat.core.xmpp.MAX_HISTORY_PAGE_FAILURES
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.core.xmpp.advanceOlderCursor
import com.ethora.chat.core.xmpp.findMessageIndex
import com.ethora.chat.core.xmpp.loadJumpWindow
import com.ethora.chat.core.xmpp.loadNewerWindowPage
import com.ethora.chat.core.xmpp.loadOlderWindowPage
import com.ethora.chat.core.xmpp.oldestArchiveCursor
import com.ethora.chat.core.xmpp.replyParentIdOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
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

    // ---- Jump to message (web useJumpToMessage) ----
    /** Archive messages shown in place of the live list while a jump is open; null = live list. */
    private val _jumpWindow = MutableStateFlow<JumpWindow?>(null)
    val jumpWindow: StateFlow<JumpWindow?> = _jumpWindow.asStateFlow()

    /** Message the list should scroll to and flash. The list calls [consumeJumpTarget]. */
    private val _jumpTarget = MutableStateFlow<JumpTarget?>(null)
    val jumpTarget: StateFlow<JumpTarget?> = _jumpTarget.asStateFlow()

    /** Shown when a jump cannot place its message: the archived card, or "too far back". */
    private val _jumpNotice = MutableStateFlow<JumpNotice?>(null)
    val jumpNotice: StateFlow<JumpNotice?> = _jumpNotice.asStateFlow()

    private var windowBusy = false

    // Older-history paging state, owned here (web cursorRef / exhaustedRef / failuresRef).
    private var pagingCursor: Long? = null
    private var pagingFailures = 0
    private var pagingExhausted = false
    
    private val _hasMoreMessages = MutableStateFlow(true)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _scrollRestoreAnchor = MutableStateFlow<String?>(null)
    val scrollRestoreAnchor: StateFlow<String?> = _scrollRestoreAnchor.asStateFlow()

    // Coalesced ack-catchup state: at most one polling coroutine per room.
    // The deadline is the latest "5 s from now" extension seen across all
    // recent sends. The job reference is consulted under [ackCatchupLock] so
    // [schedulePendingFallback] doesn't launch a parallel loop.
    private val ackCatchupLock = Any()
    @Volatile private var ackCatchupDeadline: Long = 0L
    private var ackCatchupJob: kotlinx.coroutines.Job? = null

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
                    publishLive(it)
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

        viewModelScope.launch {
            restoreQueuedMediaMessages()
            processPendingMediaQueue()
        }

        viewModelScope.launch {
            ConnectionStore.state
                .map { it.status }
                .distinctUntilChanged()
                .filter { it == ChatConnectionStatus.ONLINE }
                .collect {
                    processPendingMediaQueue()
                }
        }
        
        // A "scroll to this message" request for this room (search hit, quote, link).
        viewModelScope.launch {
            JumpRequestStore.pending.collect { jump ->
                if (jump == null || jump.roomJid != room.jid) return@collect
                JumpRequestStore.consume(jump.at)
                if (JumpRequestStore.isExpired(jump)) return@collect
                runJump(jump)
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
                    publishLive(persistedMessages.sortedBy { it.timestamp ?: it.date.time })
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
                        publishLive(afterWaitMessages.sortedBy { it.timestamp ?: it.date.time })
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
            publishLive(sorted)
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
     * The `mainMessage` JSON a reply carries (web createMainMessageForThread), built
     * from the parent when it is loaded, else from its id alone. Null when not a reply.
     */
    private fun replyMainMessage(parentId: String?): String? {
        if (parentId == null) return null
        val parent = MessageStore.getMessagesForRoom(room.jid)
            .firstOrNull { ThreadSupport.threadKey(it) == parentId || it.id == parentId }
            ?: Message(
                id = parentId,
                user = com.ethora.chat.core.models.User(id = ""),
                date = java.util.Date(),
                body = "",
                roomJid = room.jid
            )
        return ThreadSupport.createMainMessageForThread(parent)
    }

    /**
     * Send message
     */
    fun sendMessage(text: String, parentMessageId: String? = null, alsoSendToRoom: Boolean = false) {
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
            
            // Create the optimistic row before the network send. Waiting for
            // sendMessage() here can block on room presence/socket state and
            // makes the input feel laggy even though the tap handler fired.
            val messageId = "send-text-message:${UUID.randomUUID()}"
            val lastKnownTs = MessageStore.lastKnownTimestamp(room.jid)
            val optimisticTs = maxOf(System.currentTimeMillis(), lastKnownTs + 1)
            // Web `sendMs(text, room, isReply, showInChannel, mainMessage)`: a reply
            // carries the parent as JSON (createMainMessageForThread), keyed by the
            // parent's archive id so web and Android read the same reference.
            val mainMessage = replyMainMessage(finalParentMessageId)
            val optimisticMessage = Message(
                id = messageId,
                body = finalText,
                user = currentUser,
                date = java.util.Date(optimisticTs),
                timestamp = optimisticTs,
                roomJid = room.jid,
                pending = true,
                xmppId = messageId,
                xmppFrom = "${room.jid}/${currentUser.id}",
                isReply = finalParentMessageId != null,
                mainMessage = mainMessage,
                showInChannel = if (finalParentMessageId != null) alsoSendToRoom.toString() else null
            )
            MessageStore.addMessage(room.jid, optimisticMessage)
            android.util.Log.d("ChatRoomViewModel", "Created optimistic message before send, ID: $messageId")

            val sentMessageId = xmppClient?.sendMessage(
                roomJID = room.jid,
                messageBody = finalText,
                firstName = currentUser.firstName,
                lastName = currentUser.lastName,
                photo = currentUser.profileImage,
                walletAddress = currentUser.walletAddress,
                isReply = finalParentMessageId != null,
                showInChannel = alsoSendToRoom && finalParentMessageId != null,
                mainMessage = mainMessage,
                customId = messageId
            )

            if (sentMessageId != null) {
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
                // Mark the optimistic row as send-failed so the bubble switches
                // immediately to the "Sending failed. Tap to retry or delete."
                // state without waiting for the 6 s pending-timeout. Use the
                // downgrade-safe helper so we don't clobber a row that the
                // server echo already confirmed during this round-trip — the
                // spec forbids `sent → error` transitions on stale signals.
                val flipped = MessageStore.markSendFailedIfStillPending(room.jid, messageId)
                if (flipped) {
                    ChatEventDispatcher.emit(
                        ChatEvent.MessageFailed(
                            roomJid = room.jid,
                            messageType = MessageType.TEXT,
                            text = finalText,
                            error = IllegalStateException("sendMessage returned null")
                        )
                    )
                } else {
                    android.util.Log.d(
                        "ChatRoomViewModel",
                        "Discarded stale send-null for $messageId — server already echoed it"
                    )
                }
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
     * Load older messages when the reader scrolls to the top (web loadMoreMessages).
     * While a jump window is shown, the same gesture pages the window instead.
     *
     * The cursor is the server's archive id of the oldest row, never a
     * millisecond timestamp: the server reads a timestamp as a far older id and
     * answers with the first page of the archive.
     */
    fun loadMoreMessages(@Suppress("UNUSED_PARAMETER") idOfMessageBefore: String? = null) {
        if (_jumpWindow.value != null) {
            loadOlderInWindow()
            return
        }
        if (isLoadingMoreInProgress) {
            android.util.Log.d("ChatRoomViewModel", "Skipping loadMore: already in progress")
            return
        }
        viewModelScope.launch {
            isLoadingMoreInProgress = true
            _isLoadingMore.value = true
            try {
                val oldest = _messages.value.firstOrNull { it.id != "delimiter-new" }
                if (oldest == null) {
                    android.util.Log.d("ChatRoomViewModel", "No messages to paginate from")
                } else {
                    _scrollRestoreAnchor.value = oldest.id
                    fetchOlderPage()
                }
            } catch (e: CancellationException) {
                // Expected when coroutine is cancelled - don't log as error
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error loading more messages", e)
            } finally {
                isLoadingMoreInProgress = false
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * One page of older history into the store, with the server's cursor.
     * Returns false when no request was made (not connected, nothing to page
     * from, or the history is exhausted).
     */
    private suspend fun fetchOlderPage(): Boolean {
        val client = xmppClient ?: return false
        if (pagingExhausted) return false
        val before = pagingCursor ?: oldestArchiveCursor(liveMessages()) ?: run {
            android.util.Log.d("ChatRoomViewModel", "No archive id to page from in ${room.jid}")
            return false
        }
        if (!client.isFullyConnected() && !client.ensureConnected(5_000)) {
            android.util.Log.w("ChatRoomViewModel", "Skipping loadMoreMessages: client not connected for ${room.jid}")
            return false
        }
        client.promoteRoomHistory(room.jid)
        val page = client.getHistoryPage(room.jid, max = 30, beforeMessageId = before.toString())
        if (page.messages.isNotEmpty()) {
            MessageStore.addMessages(room.jid, page.messages)
        }
        val state = advanceOlderCursor(before, page.ok, page.complete, page.first)
        pagingFailures = if (page.ok) 0 else pagingFailures + 1
        pagingCursor = state.cursor
        pagingExhausted = state.exhausted || pagingFailures >= MAX_HISTORY_PAGE_FAILURES
        _hasMoreMessages.value = !pagingExhausted
        android.util.Log.d(
            "ChatRoomViewModel",
            "History page before=$before got=${page.messages.size} ok=${page.ok} complete=${page.complete} exhausted=$pagingExhausted"
        )
        return true
    }

    /** Opens the window around a message, or scrolls the live list when the message is already there. */
    private suspend fun runJump(jump: PendingJump) {
        val content = JumpContent(createdAt = jump.createdAt, body = jump.body)
        _jumpNotice.value = null

        if (_jumpWindow.value != null) {
            val live = liveMessages()
            val liveIndex = findMessageIndex(live, jump.ids, content)
            if (liveIndex >= 0) {
                exitJumpWindow()
                _jumpTarget.value = JumpTarget(live[liveIndex].id, jump.at)
                return
            }
        } else {
            val shown = _messages.value
            val index = findMessageIndex(shown, jump.ids, content)
            if (index >= 0) {
                _jumpTarget.value = JumpTarget(shown[index].id, jump.at)
                return
            }
        }

        val client = xmppClient
        if (client == null || (!client.isFullyConnected() && !client.ensureConnected(10_000))) {
            showJumpNotice(jump)
            return
        }
        when (val result = loadJumpWindow(client, room.jid, jump.ids, content)) {
            is JumpWindowResult.Found -> showJumpWindow(result.window, jump.at)
            JumpWindowResult.Missing -> showJumpNotice(jump)
            JumpWindowResult.Unavailable -> {
                // No window could be asked for: page older history until the message shows up.
                val id = pageUntilFound(jump, content)
                if (id != null) _jumpTarget.value = JumpTarget(id, jump.at) else showJumpNotice(jump)
            }
        }
    }

    private suspend fun pageUntilFound(jump: PendingJump, content: JumpContent): String? {
        var pages = 0
        while (true) {
            val live = liveMessages()
            val index = findMessageIndex(live, jump.ids, content)
            if (index >= 0) return live[index].id
            if (pages >= MAX_JUMP_HISTORY_PAGES || !fetchOlderPage()) return null
            pages += 1
        }
    }

    private fun showJumpWindow(window: JumpWindow, at: Long) {
        _jumpWindow.value = window
        _messages.value = window.messages
        _jumpTarget.value = JumpTarget(window.targetId, at)
    }

    private fun showJumpNotice(jump: PendingJump) {
        val preview = jump.preview
        _jumpNotice.value = if (preview != null) {
            JumpNotice.Archived(preview, jump.at)
        } else {
            JumpNotice.NotFound(jump.at)
        }
    }

    /** "Jump to latest messages": back to the live list. */
    fun jumpToLatest() {
        if (_jumpWindow.value != null) exitJumpWindow()
    }

    private fun exitJumpWindow() {
        _jumpWindow.value = null
        _messages.value = liveMessages()
    }

    /** Pages the window toward older messages (the list reached its top). */
    fun loadOlderInWindow() = pageWindow(older = true)

    /** Pages the window toward newer messages (the list reached its bottom). */
    fun loadNewerInWindow() = pageWindow(older = false)

    private fun pageWindow(older: Boolean) {
        val client = xmppClient ?: return
        val window = _jumpWindow.value ?: return
        if (windowBusy) return
        if (older && !window.hasOlder) return
        if (!older && !window.hasNewer) return
        viewModelScope.launch {
            windowBusy = true
            try {
                if (older) {
                    val page = loadOlderWindowPage(client, window) ?: return@launch
                    val current = _jumpWindow.value ?: return@launch
                    if (current.targetId != window.targetId) return@launch
                    _scrollRestoreAnchor.value = current.messages.firstOrNull()?.id
                    updateWindow(
                        current.copy(
                            messages = mergeWindowMessages(current.messages, page.messages),
                            olderCursor = page.olderCursor,
                            hasOlder = page.hasOlder
                        )
                    )
                } else {
                    val page = loadNewerWindowPage(client, window) ?: return@launch
                    val current = _jumpWindow.value ?: return@launch
                    if (current.targetId != window.targetId) return@launch
                    updateWindow(
                        current.copy(
                            messages = mergeWindowMessages(current.messages, page.messages),
                            newerCursor = page.newerCursor,
                            hasNewer = page.hasNewer
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error paging jump window", e)
            } finally {
                windowBusy = false
            }
        }
    }

    private fun updateWindow(window: JumpWindow) {
        _jumpWindow.value = window
        _messages.value = window.messages
    }

    /** The list scrolled to [JumpTarget] and flashed it. */
    fun consumeJumpTarget(at: Long) {
        if (_jumpTarget.value?.at == at) _jumpTarget.value = null
    }

    fun dismissJumpNotice() {
        _jumpNotice.value = null
    }

    /** Opens the message [parent] that a reply quotes. */
    fun jumpToQuotedMessage(parent: com.ethora.chat.core.models.Message) {
        val createdAt = java.time.Instant.ofEpochMilli(parent.timestamp ?: parent.date.time).toString()
        JumpRequestStore.request(
            roomJid = room.jid,
            ids = listOf(parent.id, parent.xmppId),
            createdAt = createdAt,
            body = parent.body,
            preview = PendingJumpPreview(
                roomJid = room.jid,
                sender = parent.user.fullName,
                body = parent.body,
                createdAt = createdAt
            )
        )
    }

    private fun liveMessages(): List<com.ethora.chat.core.models.Message> =
        (MessageStore.messages.value[room.jid] ?: emptyList()).sortedBy { it.timestamp ?: it.date.time }

    /** Writes the live list unless a jump window owns the list. */
    private fun publishLive(list: List<com.ethora.chat.core.models.Message>) {
        if (_jumpWindow.value == null) _messages.value = list
    }

    fun clearScrollRestoreAnchor() {
        _scrollRestoreAnchor.value = null
    }
    
    /**
     * Queue one media message holding every file in [files], in order. The
     * message is one stanza: each file is uploaded (secure route, web parity),
     * then a single media message goes out with the caption on <data>, and the
     * `attachments` JSON when there is more than one file. The optimistic bubble
     * stays visible while the queue retries across reconnects and foregrounds.
     */
    fun sendMediaBatch(
        files: List<Pair<File, String>>,
        caption: String? = null,
        replyToMessageId: String? = null
    ) {
        viewModelScope.launch {
            try {
                val config = ChatStore.getConfig()
                val limits = config?.attachments ?: com.ethora.chat.core.config.AttachmentsConfig()
                if (files.isEmpty()) return@launch
                val currentUser = UserStore.currentUser.value
                if (currentUser == null) {
                    android.util.Log.e("ChatRoomViewModel", "Cannot send media: user or token is null")
                    ChatEventDispatcher.emit(
                        ChatEvent.MessageFailed(
                            roomJid = room.jid,
                            messageType = MessageType.MEDIA,
                            error = IllegalStateException("User is null"),
                            metadata = mapOf("fileName" to files.first().first.name, "mimeType" to files.first().second)
                        )
                    )
                    return@launch
                }

                val accepted = files.take(limits.effectiveMaxFiles)
                if (accepted.size < files.size) {
                    android.util.Log.w("ChatRoomViewModel", "Dropping ${files.size - accepted.size} files over attachments.maxFiles=${limits.effectiveMaxFiles}")
                }

                val prepared = mutableListOf<Pair<File, String>>()
                for ((file, mimeType) in accepted) {
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

                    val (uploadFile, uploadMimeType) = prepareMediaForUpload(file, effectiveMimeType)
                    val fileSize = uploadFile.length()
                    if (fileSize > limits.effectiveMaxFileBytes) {
                        android.util.Log.e("ChatRoomViewModel", "File too large: $fileSize bytes (max: ${limits.effectiveMaxFileBytes})")
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
                    prepared += uploadFile to uploadMimeType
                }

                val (firstFile, firstMime) = prepared.first()
                // Use timestamp-based ID that will be replaced by server echo
                val messageId = "send-media-message:${UUID.randomUUID()}"
                // Same max(now, last+1) trick as text send — keeps the freshly
                // sent media at the very bottom even if server clocks drift.
                val lastKnownTs = MessageStore.lastKnownTimestamp(room.jid)
                val optimisticTs = maxOf(System.currentTimeMillis(), lastKnownTs + 1)
                val trimmedCaption = caption?.trim()?.takeIf { it.isNotEmpty() }
                val optimisticAttachments = prepared.map { (file, mime) ->
                    val localUri = "file://${file.absolutePath}"
                    MessageAttachment(
                        location = localUri,
                        locationPreview = if (mime.startsWith("image/")) localUri else null,
                        mimetype = mime,
                        originalName = file.name,
                        fileName = file.name,
                        size = file.length().toString()
                    )
                }
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
                    fileName = firstFile.name,
                    // The local file URI shows the picture while the upload runs. When the
                    // server echo returns, location and locationPreview are replaced by the CDN URLs.
                    location = "file://${firstFile.absolutePath}",
                    locationPreview = if (firstMime.startsWith("image/")) "file://${firstFile.absolutePath}" else "",
                    mimetype = firstMime,
                    originalName = firstFile.name,
                    size = firstFile.length().toString(),
                    caption = trimmedCaption,
                    attachments = if (optimisticAttachments.size > 1) optimisticAttachments else null,
                    isReply = replyToMessageId != null,
                    mainMessage = replyMainMessage(replyToMessageId)
                )
                MessageStore.addMessage(room.jid, optimisticMessage)
                android.util.Log.d("ChatRoomViewModel", "Created optimistic media message with pending=true, ID: $messageId (${prepared.size} files)")
                schedulePendingFallback(messageId)

                PendingMediaSendQueue.enqueue(
                    PendingMediaSend(
                        roomJid = room.jid,
                        messageId = messageId,
                        localFilePath = firstFile.absolutePath,
                        fileName = firstFile.name,
                        mimeType = firstMime,
                        createdAt = optimisticTs,
                        caption = trimmedCaption,
                        replyToMessageId = replyToMessageId,
                        captionMessageId = null,
                        files = prepared.map { (file, mime) ->
                            PendingMediaFile(
                                localFilePath = file.absolutePath,
                                fileName = file.name,
                                mimeType = mime
                            )
                        }
                    )
                )
                processPendingMediaQueue()
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error sending media", e)
                ChatEventDispatcher.emit(
                    ChatEvent.MessageFailed(
                        roomJid = room.jid,
                        messageType = MessageType.MEDIA,
                        error = e,
                        metadata = mapOf("fileName" to files.first().first.name, "mimeType" to files.first().second)
                    )
                )
            }
        }
    }

    /** Single-file send. Kept for existing callers; the caption now rides on the media message. */
    fun sendMedia(
        file: File,
        mimeType: String,
        retryCount: Int = 0,
        caption: String? = null,
        replyToMessageId: String? = null
    ) {
        sendMediaBatch(listOf(file to mimeType), caption, replyToMessageId)
    }

    /**
     * Combo send: one media message with the caption (web parity). Blank captions
     * are dropped.
     */
    fun sendMediaWithCaption(
        file: File,
        mimeType: String,
        caption: String,
        replyToMessageId: String? = null
    ) {
        sendMediaBatch(listOf(file to mimeType), caption, replyToMessageId)
    }

    fun processPendingMediaQueue() {
        viewModelScope.launch {
            processDuePendingMediaItems()
        }
    }

    private suspend fun processDuePendingMediaItems() {
        val currentUser = UserStore.currentUser.value ?: return
        val token = UserStore.token.value ?: currentUser.token ?: return
        val client = xmppClient ?: return
        val dueItems = PendingMediaSendQueue.dueItems()
            .filter { it.roomJid == room.jid }

        dueItems.forEach { item ->
            if (!mediaQueueInFlight.add(item.id)) return@forEach
            try {
                var working = PendingMediaSendQueue.update(item.id) {
                    it.copy(status = PendingMediaSendStatus.UPLOADING)
                } ?: return@forEach

                // Upload every file that has no payload yet. Each finished upload is saved
                // at once, so a retry after a later failure never uploads a file twice.
                val files = working.effectiveFiles.toMutableList()
                for (index in files.indices) {
                    if (files[index].uploaded != null) continue
                    val file = File(files[index].localFilePath)
                    if (!file.exists() || !file.canRead()) {
                        markPendingMediaFailed(
                            working,
                            IllegalStateException("Queued file is missing or unreadable")
                        )
                        return@forEach
                    }

                    val uploadResult = withContext(Dispatchers.IO) {
                        AuthAPIHelper.uploadFile(
                            file = file,
                            mimeType = files[index].mimeType,
                            token = token,
                            baseUrl = ChatStore.getEffectiveBaseUrl(),
                            chatName = working.roomJid.substringBefore('@')
                        )
                    }
                    if (uploadResult == null) {
                        markPendingMediaFailed(working, IllegalStateException("Upload failed"))
                        ChatEventDispatcher.emit(
                            ChatEvent.MediaUploadResult(
                                roomJid = working.roomJid,
                                fileName = working.fileName,
                                mimeType = working.mimeType,
                                success = false,
                                error = IllegalStateException("Upload failed")
                            )
                        )
                        return@forEach
                    }

                    ChatEventDispatcher.emit(
                        ChatEvent.MediaUploadResult(
                            roomJid = working.roomJid,
                            fileName = uploadResult.filename,
                            mimeType = uploadResult.mimetype,
                            success = true
                        )
                    )
                    files[index] = files[index].copy(uploaded = PendingMediaUploadPayload.from(uploadResult))
                    working = PendingMediaSendQueue.update(working.id) {
                        it.withFiles(files.toList()).copy(nextRetryAt = 0L)
                    } ?: return@forEach
                }

                val uploadResults = files.map { checkNotNull(it.uploaded).toFileUploadResult() }
                working = PendingMediaSendQueue.update(working.id) {
                    it.copy(status = PendingMediaSendStatus.READY_TO_SEND, nextRetryAt = 0L)
                } ?: return@forEach

                updateOptimisticMediaMessage(working, uploadResults)

                if (!client.isFullyConnected() && !client.ensureConnected(15_000)) {
                    markPendingMediaFailed(working, IllegalStateException("XMPP not connected"))
                    return@forEach
                }

                // Entries queued before the caption moved onto the media message still send it
                // as a follow-up text (captionMessageId is set only for those).
                val mediaCaption = if (working.captionMessageId == null) working.caption else null
                val mediaData = buildMediaData(uploadResults, currentUser, mediaCaption, working.replyToMessageId)
                val sent = try {
                    client.sendMediaMessage(working.roomJid, mediaData, working.messageId)
                } catch (e: Exception) {
                    android.util.Log.e("ChatRoomViewModel", "Error sending queued media message via XMPP", e)
                    false
                }

                if (sent) {
                    // Dispatch any companion caption text BEFORE removing the
                    // queue entry, so that if the dispatch throws / the
                    // ViewModel is cancelled, the caption isn't silently lost.
                    val pendingCaption = working.caption
                    val pendingReply = working.replyToMessageId
                    val pendingCaptionId = working.captionMessageId
                    PendingMediaSendQueue.remove(working.id, deleteLocalFile = true)
                    if (!pendingCaption.isNullOrBlank() && !pendingCaptionId.isNullOrBlank()) {
                        // Legacy entry: the optimistic caption bubble was added at send time.
                        // Send the text with customId so the echo merges into that row.
                        viewModelScope.launch {
                            val sentId = try {
                                client.sendMessage(
                                    roomJID = room.jid,
                                    messageBody = pendingCaption,
                                    firstName = currentUser.firstName,
                                    lastName = currentUser.lastName,
                                    photo = currentUser.profileImage,
                                    walletAddress = currentUser.walletAddress,
                                    isReply = pendingReply != null,
                                    mainMessage = replyMainMessage(pendingReply),
                                    customId = pendingCaptionId
                                )
                            } catch (e: Exception) {
                                android.util.Log.e(
                                    "ChatRoomViewModel",
                                    "Caption send threw after media XMPP send succeeded",
                                    e
                                )
                                null
                            }
                            if (sentId == null) {
                                // Downgrade-safe flip — if the server already
                                // echoed this caption while we were awaiting
                                // `sendMessage`, the helper returns false and
                                // we leave the row in its confirmed state.
                                MessageStore.markSendFailedIfStillPending(
                                    room.jid,
                                    pendingCaptionId
                                )
                            }
                        }
                    }
                    viewModelScope.launch { triggerFastAckFetch() }
                    ChatEventDispatcher.emit(
                        ChatEvent.MessageSent(
                            roomJid = working.roomJid,
                            messageId = working.messageId,
                            messageType = MessageType.MEDIA,
                            user = currentUser,
                            metadata = mapOf(
                                "location" to uploadResults.first().location,
                                "mimetype" to uploadResults.first().mimetype
                            )
                        )
                    )
                } else {
                    markPendingMediaFailed(working, IllegalStateException("Failed to send media message via XMPP"))
                }
            } finally {
                mediaQueueInFlight.remove(item.id)
            }
        }
    }

    private fun markPendingMediaFailed(item: PendingMediaSend, error: Throwable) {
        // Honor RetryConfig: when autoRetry is false (default) the item goes
        // straight to PERMANENTLY_FAILED and we never schedule a delayed retry.
        // The in-flight send that just failed is allowed to complete naturally
        // (we are inside its finally block); future scheduling is suppressed.
        val retryCfg = ChatStore.getConfig()?.retryConfig
            ?: com.ethora.chat.core.config.RetryConfig()
        val failed = PendingMediaSendQueue.update(item.id) {
            it.failedForRetry(
                autoRetry = retryCfg.autoRetry,
                maxAttempts = retryCfg.maxAttempts
            )
        }
        ChatEventDispatcher.emit(
            ChatEvent.MessageFailed(
                roomJid = item.roomJid,
                messageType = MessageType.MEDIA,
                error = error,
                metadata = mapOf("fileName" to item.fileName, "mimeType" to item.mimeType)
            )
        )
        if (!retryCfg.autoRetry) return
        failed?.let { failedItem ->
            // Only schedule a follow-up if the item is still in the retry
            // window. PERMANENTLY_FAILED items are user-action only.
            if (failedItem.status != PendingMediaSendStatus.FAILED_WAITING_RETRY) return
            viewModelScope.launch {
                val delayMs = (failedItem.nextRetryAt - System.currentTimeMillis()).coerceAtLeast(0L)
                kotlinx.coroutines.delay(delayMs)
                processPendingMediaQueue()
            }
        }
    }

    private fun updateOptimisticMediaMessage(
        item: PendingMediaSend,
        uploadResults: List<com.ethora.chat.core.networking.FileUploadResult>
    ) {
        val existing = MessageStore.getMessagesForRoom(item.roomJid)
            .firstOrNull { it.id == item.messageId }
        val first = uploadResults.first()
        val updated = existing?.copy(
            location = first.location,
            locationPreview = first.locationPreview,
            fileName = first.filename,
            mimetype = first.mimetype,
            originalName = first.originalName,
            size = first.size,
            attachments = if (uploadResults.size > 1) uploadResults.map { it.toMessageAttachment() } else null,
            pending = true
        ) ?: return
        MessageStore.updateMessage(item.roomJid, updated)
    }

    private fun com.ethora.chat.core.networking.FileUploadResult.toMessageAttachment(): MessageAttachment =
        MessageAttachment(
            attachmentId = attachmentId,
            location = location,
            locationPreview = locationPreview,
            mimetype = mimetype,
            originalName = originalName,
            fileName = filename,
            size = size,
            duration = duration
        )

    private fun buildMediaData(
        uploadResults: List<com.ethora.chat.core.networking.FileUploadResult>,
        currentUser: com.ethora.chat.core.models.User,
        caption: String?,
        replyToMessageId: String? = null
    ): Map<String, Any> {
        val uploadResult = uploadResults.first()
        val xmppHost = ChatStore.getEffectiveXmppSettings().host
        val senderJID = currentUser.xmppUsername?.let { "$it@$xmppHost" } ?: ""
        return buildMap {
            put("senderJID", senderJID)
            put("senderFirstName", currentUser.firstName ?: "")
            put("senderLastName", currentUser.lastName ?: "")
            put("senderWalletAddress", currentUser.walletAddress ?: "")
            put("isSystemMessage", "false")
            put("tokenAmount", "0")
            put("receiverMessageId", "0")
            put("mucname", room.name ?: "")
            put("photoURL", currentUser.profileImage ?: "")
            put("isMediafile", "true")
            put("createdAt", uploadResult.createdAt)
            put("expiresAt", uploadResult.expiresAt ?: "")
            put("fileName", uploadResult.filename)
            put("isVisible", uploadResult.isVisible?.toString() ?: "true")
            put("location", uploadResult.location)
            put("locationPreview", uploadResult.locationPreview ?: "")
            put("mimetype", uploadResult.mimetype)
            put("originalName", uploadResult.originalName)
            put("ownerKey", uploadResult.ownerKey ?: "")
            put("size", uploadResult.size)
            put("duration", uploadResult.duration ?: "")
            put("updatedAt", uploadResult.updatedAt ?: "")
            put("userId", uploadResult.userId ?: currentUser.id)
            put("waveForm", uploadResult.waveForm ?: "")
            put("attachmentId", uploadResult.attachmentId ?: "")
            // Web: `attachments` only when there is more than one file; the flat
            // fields above describe the first file for readers that predate it.
            if (uploadResults.size > 1) {
                put("attachments", MessageAttachments.serialize(uploadResults.map { it.toMessageAttachment() }))
            }
            if (!caption.isNullOrBlank()) put("caption", caption)
            // Web parity: a reply to a message carries the parent reference JSON.
            put("isReply", (replyToMessageId != null).toString())
            put("showInChannel", "true")
            put("mainMessage", replyMainMessage(replyToMessageId) ?: "")
            put("roomJid", room.jid)
            put("push", "true")
        }
    }

    private fun restoreQueuedMediaMessages() {
        val currentUser = UserStore.currentUser.value ?: return
        PendingMediaSendQueue.items.value
            .filter { it.roomJid == room.jid }
            .forEach { item ->
                val exists = MessageStore.getMessagesForRoom(room.jid)
                    .any { it.id == item.messageId }
                if (exists) return@forEach
                val files = item.effectiveFiles
                val first = files.first()
                val upload = first.uploaded
                val localUri = "file://${first.localFilePath}"
                val message = Message(
                    id = item.messageId,
                    body = "media",
                    user = currentUser,
                    date = Date(item.createdAt),
                    timestamp = item.createdAt,
                    roomJid = item.roomJid,
                    pending = true,
                    isDeleted = false,
                    xmppId = item.messageId,
                    xmppFrom = "${item.roomJid}/${currentUser.id}",
                    isSystemMessage = "false",
                    isMediafile = "true",
                    fileName = upload?.filename ?: first.fileName,
                    location = upload?.location ?: localUri,
                    locationPreview = upload?.locationPreview ?: if (first.mimeType.startsWith("image/")) localUri else "",
                    mimetype = upload?.mimetype ?: first.mimeType,
                    originalName = upload?.originalName ?: first.fileName,
                    size = upload?.size ?: File(first.localFilePath).length().toString(),
                    caption = item.caption?.takeIf { item.captionMessageId == null },
                    attachments = if (files.size > 1) {
                        files.map { file ->
                            val payload = file.uploaded
                            val fileUri = "file://${file.localFilePath}"
                            MessageAttachment(
                                attachmentId = payload?.attachmentId,
                                location = payload?.location ?: fileUri,
                                locationPreview = payload?.locationPreview
                                    ?: if (file.mimeType.startsWith("image/")) fileUri else null,
                                mimetype = payload?.mimetype ?: file.mimeType,
                                originalName = payload?.originalName ?: file.fileName,
                                fileName = payload?.filename ?: file.fileName,
                                size = payload?.size ?: File(file.localFilePath).length().toString()
                            )
                        }
                    } else {
                        null
                    }
                )
                MessageStore.addMessage(room.jid, message)
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
     * Coalesced port of web's `scheduleAckCatchup` — useSendMessage.tsx L205-236.
     *
     * After a send, polls MAM (presence + 20-message fetch) until either every
     * pending row in the room transitions out of `pending` state or the
     * deadline elapses. Each call to this function extends the deadline by
     * 5 s but does NOT launch a new polling coroutine if one is already
     * running for this room — the existing loop simply observes the new
     * deadline on its next iteration.
     *
     * Why coalesce: spam-send (the user firing 20+ messages in a few seconds)
     * previously launched 20 concurrent polling coroutines, each doing its
     * own presence + 20-message MAM fetch every 700 ms. That hammered the
     * server, churned the message store CoW, and provided no benefit over a
     * single shared loop because every poll fetches the same newest page.
     *
     * Web's final safety net (forcibly clearing `pending`) is handled for us
     * by `MessageStore.schedulePendingTimeout` which fires at 6 s regardless
     * of ViewModel lifecycle.
     */
    private fun schedulePendingFallback(@Suppress("UNUSED_PARAMETER") messageId: String) {
        // 30 s catchup window — matches `MessageStore.schedulePendingTimeout`'s
        // default so the polling stays alive until the same moment the
        // optimistic row would be flipped to `sendFailed`. The previous 5 s
        // window left a 25 s gap between "polling stops" and "timeout fires"
        // during which a slow server echo could only be reconciled by a
        // separate user-driven trigger (another send, room re-entry). Field
        // reports of "message flashes failed, then becomes sent ~15 s later"
        // map directly to that gap.
        val newDeadline = System.currentTimeMillis() + 30_000L
        synchronized(ackCatchupLock) {
            if (newDeadline > ackCatchupDeadline) {
                ackCatchupDeadline = newDeadline
            }
            if (ackCatchupJob?.isActive == true) {
                // Existing loop will pick up the extended deadline on its next
                // iteration. No need for a parallel coroutine.
                return
            }
            ackCatchupJob = viewModelScope.launch { runAckCatchupLoop() }
        }
    }

    /**
     * Pollster body for [schedulePendingFallback]. Runs at most one instance
     * per room. Exits when either the room has no more pending messages or
     * the deadline (shared, extensible) has elapsed. The trailing
     * synchronized re-check guards against the race where a new send extends
     * the deadline AFTER the inner loop has broken but BEFORE we mark the
     * job inactive — without it, the new send would see `isActive == true`,
     * skip launching, and its pending row would have no poll cover.
     */
    private suspend fun runAckCatchupLoop() {
        kotlinx.coroutines.delay(150)
        outer@ while (true) {
            while (true) {
                val due = ackCatchupDeadline
                if (System.currentTimeMillis() >= due) break
                if (!hasPendingMessagesInRoom()) break

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
                if (!hasPendingMessagesInRoom()) break
                kotlinx.coroutines.delay(700)
            }
            // Re-check inside the lock — if a send between our break and now
            // extended the deadline AND added a pending row, restart the
            // inner loop instead of letting the gap drop the new bubble.
            val cont = synchronized(ackCatchupLock) {
                ackCatchupDeadline > System.currentTimeMillis() && hasPendingMessagesInRoom()
            }
            if (!cont) return
        }
    }

    private fun hasPendingMessagesInRoom(): Boolean =
        MessageStore.getMessagesForRoom(room.jid).any { it.pending == true }

    companion object {
        // Room-keyed "last fast-ack" timestamps — shared across ViewModel
        // instances so switching chats doesn't reset the 600 ms throttle.
        private val fastAckLastByRoom = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val mediaQueueInFlight = Collections.newSetFromMap(
            ConcurrentHashMap<String, Boolean>()
        )
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
                // If the row is still pending, its id is the optimistic UUID
                // we generated locally — the server matches `<replace>` by
                // its own stanza-id (which becomes `Message.id` only after
                // the echo's reconcile). Sending now would silently no-op,
                // which is the "edit right after send doesn't work" bug.
                // Wait for reconcile, then use the row's CURRENT id.
                val wireId = awaitReconciledId(messageId) ?: messageId
                xmppClient?.editMessage(room.jid, wireId, newText)
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error editing message", e)
            }
        }
    }

    /**
     * Wait until the row identified by [originalId] is no longer pending and
     * return the id Ethora's server uses for `<replace>` / `<delete>`
     * lookups — namely its XEP-0359 stanza-id (in `Message.archiveId`).
     *
     * The reconcile in `MessageStore` keeps `Message.id` as the local
     * optimistic UUID for UI stability, so we can't rely on `id` for the
     * wire. The parser fills `archiveId` from the `<stanza-id>` element of
     * the echo / archive — that's the value the server indexes against.
     *
     * Looks up the row by `id` OR `xmppId` so we find it regardless of
     * reconcile stage. Returns `null` after [timeoutMs] — callers should
     * fall back to the original id (best effort; the server almost
     * certainly won't match an optimistic UUID, but at least the local
     * state is right).
     */
    private suspend fun awaitReconciledId(
        originalId: String,
        timeoutMs: Long = 5000L
    ): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val row = MessageStore.getMessagesForRoom(room.jid).firstOrNull {
                it.id == originalId || it.xmppId == originalId
            }
            if (row != null && row.pending != true) {
                return row.archiveId?.takeIf { it.isNotBlank() } ?: row.id
            }
            kotlinx.coroutines.delay(50)
        }
        return null
    }

    /**
     * Resend a still-pending or send-failed text message. Drops the stale
     * optimistic row and runs [sendMessage] again with the same body + reply
     * target, so the user gets a fresh xmpp send + echo window. Also handles
     * the media path via [PendingMediaSendQueue.retryNow].
     */
    fun resendMessage(messageId: String) {
        val existing = MessageStore.getMessagesForRoom(room.jid)
            .firstOrNull { it.id == messageId } ?: return
        // Allow retry for both still-pending (e.g. user tapped before timeout)
        // and explicitly send-failed messages.
        if (existing.pending != true && existing.sendFailed != true) return
        val isMedia = existing.isMediafile == "true" ||
            !existing.location.isNullOrBlank() ||
            !existing.fileName.isNullOrBlank()
        if (isMedia) {
            val queued = PendingMediaSendQueue.retryNow(messageId)
            if (queued == null) {
                android.util.Log.w("ChatRoomViewModel", "Retry requested for media message without queued item ($messageId)")
                return
            }
            MessageStore.updateMessage(room.jid, existing.copy(pending = true, sendFailed = false))
            processPendingMediaQueue()
            return
        }
        val body = existing.body
        val parent = existing.mainMessage
        MessageStore.removeMessage(room.jid, messageId)
        sendMessage(body, parent)
    }

    /**
     * Delete message.
     *
     * Field reports: "deletion stopped working when I delete right after
     * sending / when I send media+caption / just delete in general". Root
     * cause: the previous branch for pending/sendFailed rows used
     * `removeMessage` (wipes the row entirely) and did NOT send the XMPP
     * `<delete/>` stanza. The race was:
     *
     *   1. Send "hello"  → optimistic row {id=X, pending=true}
     *   2. Delete         → removeMessage wipes the row, no delete stanza sent
     *   3. Server echoes the original send
     *   4. addMessage finds no local row to merge → adds it as a NEW row
     *   5. The "deleted" message reappears
     *
     * Worse, the server still holds the message — peers see it un-deleted.
     *
     * Old code only papered over this because the pending window was 6 s,
     * so step 2 had to happen in a sliver. Extending the pending window to
     * 30 s for slow networks turned the same race into a routine bug.
     *
     * New behaviour:
     *   • Queued-media row where the file has NOT been uploaded yet
     *     (server has no idea this message exists): keep the old fast path
     *     — drop the queue entry, remove locally, done.
     *   • Queued caption (media still queued, caption send hasn't fired):
     *     null the caption fields on the queue entry so the caption never
     *     gets transmitted; remove the optimistic caption row locally.
     *   • Everything else, including still-pending text and send-failed:
     *     mark-as-deleted locally (so the merge in `addMessage` doesn't
     *     un-delete on echo) AND fire the XMPP `<delete/>` stanza. The
     *     server processes stanzas in send order, so even if the original
     *     send is still in-flight, the delete lands right after it and
     *     the peers see the tombstone.
     *
     * Paired guard in `MessageStore.addMessage` / `addMessages`: the
     * merge branch now preserves `isDeleted = true` on the existing
     * optimistic row so a late echo cannot resurrect a locally-deleted
     * message.
     */
    fun deleteMessage(messageId: String) {
        val existing = MessageStore.getMessagesForRoom(room.jid)
            .firstOrNull { it.id == messageId }
        val queuedMedia = PendingMediaSendQueue.getByMessageId(messageId)
        if (queuedMedia != null) {
            PendingMediaSendQueue.remove(queuedMedia.id, deleteLocalFile = true)
            MessageStore.removeMessage(room.jid, messageId)
            return
        }
        // Was this id the captionMessageId of a queue entry whose media hasn't
        // sent yet? If so, just suppress the caption — server hasn't seen it.
        val queuedCaption = PendingMediaSendQueue.items.value
            .firstOrNull { it.captionMessageId == messageId }
        if (queuedCaption != null) {
            PendingMediaSendQueue.update(queuedCaption.id) {
                it.copy(caption = null, captionMessageId = null)
            }
            MessageStore.removeMessage(room.jid, messageId)
            return
        }
        // Server-side path: pending, sendFailed, or fully-confirmed — all
        // three need the tombstone applied locally AND the XMPP delete
        // dispatched so peers converge. The merge guard in MessageStore
        // keeps the local deletion sticky against the in-flight echo.
        MessageStore.markMessageAsDeleted(room.jid, messageId)
        ChatEventDispatcher.emit(
            ChatEvent.MessageDeleted(
                roomJid = room.jid,
                messageId = messageId
            )
        )
        viewModelScope.launch {
            try {
                // Same defer-until-reconciled rationale as `editMessage` —
                // `<delete>` is matched server-side by stanza-id, not by the
                // optimistic UUID. Sending while still pending is a silent
                // no-op for peers.
                val wireId = awaitReconciledId(messageId) ?: messageId
                xmppClient?.deleteMessage(room.jid, wireId)
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error deleting message", e)
            }
        }
    }
    
    /**
     * Send reaction for a message by id. Kept for existing callers; the UI uses
     * [toggleReaction], which takes the message itself.
     */
    fun sendReaction(messageId: String, emoji: String) {
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        toggleReaction(message, emoji)
    }

    /**
     * Toggles my [emoji] on [message] and sends my full new set, as web does
     * (`handleReactionMessage` in Message.tsx): remove it when present, else add.
     * The target is the archive id, which is what web and the server key on.
     * Nothing is applied locally: the groupchat reflection updates every occupant,
     * the sender included.
     */
    fun toggleReaction(message: Message, emoji: String) {
        if (message.pending == true || message.isDeleted == true) return
        val currentUser = UserStore.currentUser.value
        if (currentUser == null) {
            android.util.Log.e("ChatRoomViewModel", "Cannot send reaction: user is null")
            return
        }
        viewModelScope.launch {
            try {
                val myLocalPart = currentUser.xmppUsername?.substringBefore("@")?.takeIf { it.isNotBlank() }
                    ?: currentUser.id
                val mine = message.reaction?.get(myLocalPart)?.emoji.orEmpty()
                val next = if (mine.contains(emoji)) mine.filter { it != emoji } else mine + emoji
                xmppClient?.sendMessageReaction(room.jid, ThreadSupport.threadKey(message), next)
                ChatEventDispatcher.emit(
                    ChatEvent.ReactionSent(
                        roomJid = room.jid,
                        messageId = message.id,
                        emoji = emoji
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "Error sending reaction", e)
            }
        }
    }

    private val _threadLoading = MutableStateFlow(false)
    /** True while older thread replies are being fetched from the archive. */
    val threadLoading: StateFlow<Boolean> = _threadLoading.asStateFlow()

    /**
     * Pages the archive backwards until the thread's parent is reached, so every
     * reply of [parentKey] is in the store. Web `ThreadWrapper.loadMoreMessages`:
     * page size 100 (web THREAD_HISTORY_PAGE_SIZE), one request in flight, and stop
     * once the cursor is at or before the parent (every reply is newer than it).
     */
    suspend fun loadThreadHistory(parentKey: String, pageSize: Int = 100, maxPages: Int = 50) {
        val parentNumeric = parentKey.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return
        val client = xmppClient ?: return
        _threadLoading.value = true
        try {
            var previousCursor = Long.MAX_VALUE
            repeat(maxPages) {
                val cursor = MessageStore.getMessagesForRoom(room.jid)
                    .mapNotNull { ThreadSupport.numericKey(it) }
                    .minOrNull() ?: return
                if (cursor <= parentNumeric || cursor >= previousCursor) return
                previousCursor = cursor
                if (!client.isFullyConnected() && !client.ensureConnected(5_000)) return
                client.promoteRoomHistory(room.jid)
                val page = client.getHistory(room.jid, max = pageSize, beforeMessageId = cursor.toString())
                if (page.isEmpty()) return
                MessageStore.addMessages(room.jid, page)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChatRoomViewModel", "loadThreadHistory failed for ${room.jid}", e)
        } finally {
            _threadLoading.value = false
        }
    }
}

/** Where the list should scroll. [at] identifies the request, so it is acted on once. */
data class JumpTarget(val messageId: String, val at: Long)

/** Why a jump could not place its message in the transcript. */
sealed interface JumpNotice {
    val at: Long

    /** The message is known (a search hit or quote): show it in a card. */
    data class Archived(val preview: PendingJumpPreview, override val at: Long) : JumpNotice

    /** Nothing is known about the message and the archive does not have it. */
    data class NotFound(override val at: Long) : JumpNotice
}

private const val MAX_JUMP_HISTORY_PAGES = 40
