package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.xmpp.TimestampUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Message store for managing messages state
 * Persists messages to Room Database (matches web: redux-persist with localStorage)
 */
object MessageStore {
    // -------- Ordering: port of web's `compareMessageOrder` (roomsSlice.ts) --------

    /** Matches web's getMessageTimestampValue — 4-source fallback using
     *  TimestampUtils.getTimestampFromUnknown so numeric chunks inside id
     *  strings ("send-text-message-1704067200000") still resolve. */
    private fun messageTimestampValue(m: Message): Long {
        val stored = m.timestamp ?: 0L
        if (stored > 0) return stored
        return TimestampUtils.getTimestampFromUnknown(m.date).takeIf { it > 0 }
            ?: TimestampUtils.getTimestampFromUnknown(m.xmppId).takeIf { it > 0 }
            ?: TimestampUtils.getTimestampFromUnknown(m.id).takeIf { it > 0 }
            ?: m.date.time
    }

    /** Web's compareMessageOrder: ts asc → pending last → stable key → body. */
    private val messageOrder: Comparator<Message> = Comparator { a, b ->
        val tsA = messageTimestampValue(a)
        val tsB = messageTimestampValue(b)
        if (tsA != tsB) return@Comparator tsA.compareTo(tsB)
        val pendingA = if (a.pending == true) 1 else 0
        val pendingB = if (b.pending == true) 1 else 0
        if (pendingA != pendingB) return@Comparator pendingA.compareTo(pendingB)
        val keyA = (a.xmppId ?: a.id)
        val keyB = (b.xmppId ?: b.id)
        if (keyA != keyB) return@Comparator keyA.compareTo(keyB)
        a.body.compareTo(b.body)
    }

    private fun List<Message>.sortedForUi(): List<Message> = sortedWith(messageOrder)

    // "New messages" delimiter — synthetic Message with id "delimiter-new",
    // inserted at the first unread anchor, stripped when the room is active
    // or lastViewed<=0. Runtime-only; persistence paths strip it.
    private fun normalizeDelimiterPosition(
        messages: List<Message>,
        lastViewed: Long,
        skipDelimiter: Boolean,
        roomJid: String
    ): List<Message> {
        val stripped = messages.filterNot { it.id == "delimiter-new" }
        if (skipDelimiter || lastViewed <= 0L || stripped.isEmpty()) return stripped
        val firstUnreadIndex = stripped.indexOfFirst { msg ->
            if (msg.pending == true) return@indexOfFirst false
            val ts = messageTimestampValue(msg)
            ts > lastViewed
        }
        if (firstUnreadIndex < 0) return stripped
        val delimiter = Message(
            id = "delimiter-new",
            user = com.ethora.chat.core.models.User(id = "system", name = "system"),
            date = java.util.Date(lastViewed),
            body = "New Messages",
            roomJid = roomJid,
            timestamp = lastViewed,
            isSystemMessage = "true"
        )
        return stripped.toMutableList().apply { add(firstUnreadIndex, delimiter) }
    }

    private fun skipDelimiterFor(roomJid: String): Boolean =
        roomJid == RoomStore.currentRoom.value?.jid

    /** Re-insert / strip the delimiter for [roomJid] based on current
     *  lastViewed + active state. No-op when the id sequence is unchanged. */
    fun renormalizeRoomDelimiter(roomJid: String?) {
        if (roomJid.isNullOrBlank()) return
        val current = _messages.value[roomJid] ?: return
        if (current.isEmpty()) return
        val room = RoomStore.getRoomByJid(roomJid) ?: return
        val lastViewed = room.lastViewedTimestamp ?: 0L
        val normalized = normalizeDelimiterPosition(
            messages = current,
            lastViewed = lastViewed,
            skipDelimiter = skipDelimiterFor(roomJid),
            roomJid = roomJid
        )
        val sameIds = normalized.size == current.size &&
            normalized.zip(current).all { (a, b) -> a.id == b.id }
        if (sameIds) return
        _messages.value = _messages.value.toMutableMap().apply {
            this[roomJid] = normalized
        }
    }

    /** Apply delimiter normalisation to a freshly-sorted list. */
    private fun withDelimiter(roomJid: String, sorted: List<Message>): List<Message> {
        val room = RoomStore.getRoomByJid(roomJid) ?: return sorted
        val lastViewed = room.lastViewedTimestamp ?: 0L
        return normalizeDelimiterPosition(
            messages = sorted,
            lastViewed = lastViewed,
            skipDelimiter = skipDelimiterFor(roomJid),
            roomJid = roomJid
        )
    }

    /**
     * Most recent known server-side timestamp across a room. Optimistic
     * messages set their own `timestamp` to `max(now, last + 1)` using this
     * so they never sort before a server message with a slightly drifted
     * clock. Public helper because `ChatRoomViewModel` needs it at send time.
     */
    fun lastKnownTimestamp(roomJid: String): Long {
        val list = _messages.value[roomJid] ?: return 0L
        return list.maxOfOrNull { messageTimestampValue(it) } ?: 0L
    }

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()
    
    // Message cache for persistence
    private var messageCache: MessageCache? = null
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val persistenceMutex = Mutex()
    
    /**
     * Initialize with MessageCache for persistence
     */
    @Synchronized
    fun initialize(cache: MessageCache) {
        if (messageCache === cache) {
            android.util.Log.d("MessageStore", "↻ MessageStore already initialized with this MessageCache")
            return
        }
        messageCache = cache
        android.util.Log.d("MessageStore", "✅ MessageStore initialized with MessageCache")
    }
    
    // Per-room persistence cap.
    private const val PERSIST_LIMIT_PER_ROOM = 100

    /**
     * Load messages from persistence for a room
     */
    suspend fun loadMessagesFromPersistence(roomJid: String): List<Message> {
        return try {
            val cache = messageCache
            if (cache != null) {
                val persistedMessages = cache.getLatestMessages(roomJid, PERSIST_LIMIT_PER_ROOM)
                android.util.Log.d("MessageStore", "📂 Loaded ${persistedMessages.size} messages from persistence for $roomJid")
                // Sort by timestamp (oldest first) to match store expectations and ensure takeLast() works correctly
                persistedMessages.sortedForUi()
            } else {
                android.util.Log.w("MessageStore", "⚠️ MessageCache not initialized, cannot load from persistence")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageStore", "❌ Error loading messages from persistence", e)
            emptyList()
        }
    }
    
    /** Persist message (background). Strips the runtime-only delimiter. */
    private fun persistMessage(roomJid: String, message: Message) {
        if (message.id == "delimiter-new") return
        val cache = messageCache ?: return
        persistenceScope.launch {
            persistenceMutex.withLock {
                try {
                    // Keep writes lightweight and serialized to avoid Room/SQLite OOM
                    // when many messages arrive right after login.
                    cache.saveMessage(message)
                } catch (e: Exception) {
                    android.util.Log.e("MessageStore", "❌ Error persisting message", e)
                }
            }
        }
    }

    /** Persist messages (background). Strips the runtime-only delimiter. */
    private fun persistMessages(roomJid: String, messages: List<Message>) {
        val cache = messageCache ?: return
        persistenceScope.launch {
            persistenceMutex.withLock {
                try {
                    val filtered = messages.filterNot { it.id == "delimiter-new" }
                    val messagesToSave = if (filtered.size > PERSIST_LIMIT_PER_ROOM) {
                        filtered.takeLast(PERSIST_LIMIT_PER_ROOM)
                    } else {
                        filtered
                    }
                    cache.saveMessages(messagesToSave)
                } catch (e: Exception) {
                    android.util.Log.e("MessageStore", "❌ Error persisting messages", e)
                }
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
        val normalized = withDelimiter(roomJid, messages.filterNot { it.id == "delimiter-new" })
        currentMessages[roomJid] = normalized
        _messages.value = currentMessages
        if (normalized.isNotEmpty()) {
            updateRoomLastMessage(roomJid, normalized)
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
            val xmppIdMatch = existing.xmppId != null && message.xmppId != null && existing.xmppId == message.xmppId
            
            exactIdMatch || incomingXmppIdMatchesExistingId || existingXmppIdMatchesIncomingId || xmppIdMatch
        }
        
        if (existingIndex >= 0) {
            val existingMessage = roomMessages[existingIndex]
            
            // If it's a pending message, update it and clear pending state
            if (existingMessage.pending == true) {
                // Deep merge: prefer the incoming server echo but fall back to every
                // upload-side field the client set locally before the server was
                // asked to echo it. Missing ANY of these (isMediafile/originalName/
                // body) causes the UI to render the message as plain text because
                // MessageBubble gates on `isMediafile == "true"`.
                val updatedMessage = message.copy(
                    id = existingMessage.id, // Keep original optimistic ID
                    pending = false,
                    body = if (message.body.isNotBlank()) message.body else existingMessage.body,
                    isMediafile = message.isMediafile ?: existingMessage.isMediafile,
                    location = message.location?.takeIf { it.isNotBlank() } ?: existingMessage.location,
                    locationPreview = message.locationPreview?.takeIf { it.isNotBlank() } ?: existingMessage.locationPreview,
                    fileName = message.fileName ?: existingMessage.fileName,
                    mimetype = message.mimetype ?: existingMessage.mimetype,
                    originalName = message.originalName ?: existingMessage.originalName,
                    size = message.size ?: existingMessage.size,
                    waveForm = message.waveForm ?: existingMessage.waveForm
                )
                roomMessages[existingIndex] = updatedMessage
                android.util.Log.d("MessageStore", "✅ Updated pending message ${existingMessage.id} (matched by ID/xmppId)")

                val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
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

        val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
        currentMessages[roomJid] = sorted
        _messages.value = currentMessages

        persistMessage(roomJid, message)
        updateRoomLastMessage(roomJid, sorted)
        com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
        if (message.pending == true) {
            schedulePendingTimeout(roomJid, message.id)
        }
        return false
    }

    // Tracks ids that already have a pending timeout scheduled so re-entering
    // `addMessage`/`updateMessage` with the same pending message doesn't spawn
    // a pile of parallel timers.
    private val pendingTimeoutScheduled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private fun schedulePendingTimeout(roomJid: String, messageId: String, timeoutMs: Long = 6000L) {
        val key = "$roomJid|$messageId"
        if (!pendingTimeoutScheduled.add(key)) return
        pendingScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            val currentMessages = _messages.value.toMutableMap()
            val roomMessages = currentMessages[roomJid]?.toMutableList()
            if (roomMessages == null) {
                pendingTimeoutScheduled.remove(key)
                return@launch
            }
            val index = roomMessages.indexOfFirst { it.id == messageId && it.pending == true }
            if (index >= 0) {
                // Pending didn't transition out within the timeout window — the
                // server never echoed this message. Clear `pending` so the
                // "sending..." indicator stops, but flag `sendFailed = true` so
                // the bubble persists in the failed UX state until the user
                // taps Retry or Delete. Previously the message was silently
                // marked as "delivered" which lost the failure entirely.
                val updated = roomMessages[index].copy(pending = false, sendFailed = true)
                roomMessages[index] = updated
                val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
                currentMessages[roomJid] = sorted
                _messages.value = currentMessages
                persistMessage(roomJid, updated)
                updateRoomLastMessage(roomJid, sorted)
                com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
                android.util.Log.w("MessageStore", "⏱️ Pending timeout marked sendFailed=$messageId in $roomJid")
            }
            pendingTimeoutScheduled.remove(key)
        }
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
            val existingIsMedia = existing.isMediafile == "true" || existing.location != null || existing.fileName != null || existing.body == "media"
            val windowMs = if (existingIsMedia) 60_000L else 30_000L
            if ((now - existing.date.time) > windowMs) return@indexOfFirst false
            
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
                val normalizeForMatch: (String?) -> String? = { value ->
                    value
                        ?.replace("&amp;", "&")
                        ?.replace("&lt;", "<")
                        ?.replace("&gt;", ">")
                        ?.replace("&quot;", "\"")
                        ?.replace("&apos;", "'")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                val existingLocation = normalizeForMatch(existing.location)
                val receivedLocation = normalizeForMatch(receivedMessage.location)
                val existingPreview = normalizeForMatch(existing.locationPreview)
                val receivedPreview = normalizeForMatch(receivedMessage.locationPreview)
                val locationMatch = existing.location != null && 
                                   receivedMessage.location != null && 
                                   existingLocation == receivedLocation
                val locationPreviewMatch = existing.locationPreview != null && 
                                          receivedMessage.locationPreview != null && 
                                          existingPreview == receivedPreview
                val fileNameMatch = existing.fileName != null && 
                                   receivedMessage.fileName != null && 
                                   existing.fileName == receivedMessage.fileName
                val originalNameMatch = existing.originalName != null &&
                    receivedMessage.originalName != null &&
                    existing.originalName == receivedMessage.originalName
                val mediaBodyMatch = existing.body == "media" && receivedMessage.body == "media"
                
                locationMatch || locationPreviewMatch || fileNameMatch || originalNameMatch || mediaBodyMatch
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
            
            // Update pending message with received message data, keeping the original ID.
            // Must preserve every upload-side attribute the client already set — missing
            // any one (isMediafile in particular) causes the UI to treat the message as
            // plain text and drop the photo.
            val updatedMessage = receivedMessage.copy(
                id = pendingMessage.id, // Keep original optimistic ID
                pending = false,
                body = if (receivedMessage.body.isNotBlank()) receivedMessage.body else pendingMessage.body,
                isMediafile = receivedMessage.isMediafile ?: pendingMessage.isMediafile,
                location = receivedMessage.location?.takeIf { it.isNotBlank() } ?: pendingMessage.location,
                locationPreview = receivedMessage.locationPreview?.takeIf { it.isNotBlank() } ?: pendingMessage.locationPreview,
                fileName = receivedMessage.fileName ?: pendingMessage.fileName,
                mimetype = receivedMessage.mimetype ?: pendingMessage.mimetype,
                originalName = receivedMessage.originalName ?: pendingMessage.originalName,
                size = receivedMessage.size ?: pendingMessage.size,
                waveForm = receivedMessage.waveForm ?: pendingMessage.waveForm
            )
            roomMessages[pendingIndex] = updatedMessage
            android.util.Log.d("MessageStore", "✅ Matched pending message ${pendingMessage.id} with received message ${receivedMessage.id} (${if (isFromCurrentUser) "current user" else "content match"})")

            val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
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
     * Add multiple messages to room.
     * Dedups by id/xmppId AND performs pending reconciliation for any incoming
     * message that content-matches a still-pending optimistic one. This makes
     * MAM fetches after send (fast-ack) correctly clear the pending flag even
     * when the server echo was missed by the real-time stanza router.
     */
    fun addMessages(roomJid: String, newMessages: List<Message>) {
        val currentMessages = _messages.value.toMutableMap()
        val roomMessages = currentMessages[roomJid]?.toMutableList() ?: mutableListOf()

        val messagesToAdd = mutableListOf<Message>()
        val pendingResolved = mutableListOf<Message>()

        for (incoming in newMessages) {
            // Exact id/xmppId dedup against current list (never duplicate confirmed messages).
            val duplicate = roomMessages.any { existing ->
                val exactIdMatch = existing.id == incoming.id
                val incomingXmppIdMatchesExistingId = incoming.xmppId != null && existing.id == incoming.xmppId
                val existingXmppIdMatchesIncomingId = existing.xmppId != null && existing.xmppId == incoming.id
                val xmppIdMatch = existing.xmppId != null && incoming.xmppId != null && existing.xmppId == incoming.xmppId
                exactIdMatch || incomingXmppIdMatchesExistingId || existingXmppIdMatchesIncomingId || xmppIdMatch
            }
            if (duplicate) continue

            // Pending reconciliation: if a local optimistic message content-matches this incoming,
            // replace it in place instead of adding a new row.
            val pendingIdx = findMatchingPending(roomMessages, incoming)
            if (pendingIdx >= 0) {
                val pending = roomMessages[pendingIdx]
                val merged = incoming.copy(
                    id = pending.id, // keep original optimistic ID for any callers that held onto it
                    pending = false,
                    body = if (incoming.body.isNotBlank()) incoming.body else pending.body,
                    isMediafile = incoming.isMediafile ?: pending.isMediafile,
                    location = incoming.location?.takeIf { it.isNotBlank() } ?: pending.location,
                    locationPreview = incoming.locationPreview?.takeIf { it.isNotBlank() } ?: pending.locationPreview,
                    fileName = incoming.fileName ?: pending.fileName,
                    mimetype = incoming.mimetype ?: pending.mimetype,
                    originalName = incoming.originalName ?: pending.originalName,
                    size = incoming.size ?: pending.size,
                    waveForm = incoming.waveForm ?: pending.waveForm
                )
                roomMessages[pendingIdx] = merged
                pendingResolved.add(merged)
            } else {
                messagesToAdd.add(incoming)
            }
        }

        if (messagesToAdd.isEmpty() && pendingResolved.isEmpty()) return

        roomMessages.addAll(messagesToAdd)
        // Sort ascending; newest ends up at the tail (reverseLayout=false).
        val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
        currentMessages[roomJid] = sorted
        _messages.value = currentMessages

        persistMessages(roomJid, sorted)
        updateRoomLastMessage(roomJid, sorted)
        com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
    }

    private fun findMatchingPending(roomMessages: List<Message>, incoming: Message): Int {
        val now = System.currentTimeMillis()
        return roomMessages.indexOfFirst { existing ->
            if (existing.pending != true) return@indexOfFirst false
            val isMedia = existing.isMediafile == "true" || existing.location != null ||
                existing.fileName != null || existing.body == "media"
            val windowMs = if (isMedia) 120_000L else 60_000L
            if ((now - existing.date.time) > windowMs) return@indexOfFirst false

            val existingUserId = existing.user.id.lowercase()
            val incomingUserId = incoming.user.id.lowercase()
            val existingXmpp = existing.user.xmppUsername?.split("@")?.firstOrNull()?.lowercase()
            val incomingXmpp = incoming.user.xmppUsername?.split("@")?.firstOrNull()?.lowercase()
            val userMatch = existingUserId == incomingUserId ||
                (existingXmpp != null && existingXmpp == incomingXmpp) ||
                (existingXmpp != null && existingXmpp == incomingUserId) ||
                (incomingXmpp != null && incomingXmpp == existingUserId)
            if (!userMatch) return@indexOfFirst false

            val incomingIsMedia = incoming.isMediafile == "true" || incoming.location != null ||
                incoming.fileName != null || incoming.body == "media"
            if (isMedia || incomingIsMedia) {
                val locationMatch = !existing.location.isNullOrBlank() && existing.location == incoming.location
                val fileNameMatch = !existing.fileName.isNullOrBlank() && existing.fileName == incoming.fileName
                val originalNameMatch = !existing.originalName.isNullOrBlank() && existing.originalName == incoming.originalName
                val mediaBodyMatch = existing.body == "media" && incoming.body == "media"
                locationMatch || fileNameMatch || originalNameMatch || mediaBodyMatch
            } else {
                existing.body == incoming.body
            }
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
            val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
            currentMessages[roomJid] = sorted
            _messages.value = currentMessages
            persistMessage(roomJid, message)
            updateRoomLastMessage(roomJid, sorted)
            com.ethora.chat.core.store.RoomStore.updatePendingCount(roomJid, sorted)
            // If the caller is keeping the message in pending state (e.g. after a
            // media upload completes while we wait for server echo), make sure a
            // timeout is scheduled. The ConcurrentHashMap-guarded helper dedups
            // so we don't spawn duplicate timers.
            if (message.pending == true) {
                schedulePendingTimeout(roomJid, message.id)
            }
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
            val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
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
            val sorted = withDelimiter(roomJid, roomMessages.filterNot { it.id == "delimiter-new" }.sortedForUi())
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
