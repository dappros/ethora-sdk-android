package com.ethora.chat.core.store

import com.ethora.chat.core.models.HistoryPreloadState
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.persistence.ChatPersistenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

/**
 * Room store for managing rooms state
 * Matches web Redux store pattern
 * Persists rooms to DataStore (matches web: redux-persist with localStorage)
 */
object RoomStore {
    private const val MAX_UNREAD_COUNT = 99
    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _currentRoom = MutableStateFlow<Room?>(null)
    val currentRoom: StateFlow<Room?> = _currentRoom.asStateFlow()
    
    // Per-room loading states (matches web: rooms[chatJID].isLoading)
    private val _roomLoadingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val roomLoadingStates: StateFlow<Map<String, Boolean>> = _roomLoadingStates.asStateFlow()
    
    // Global loading state (matches web: state.isLoading)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Typing timeout tracking (5 seconds)
    private val typingTimeouts = mutableMapOf<String, MutableMap<String, Job>>()
    private val typingTimeoutDuration = 5000L // 5 seconds
    
    // Persistence manager
    private var persistenceManager: ChatPersistenceManager? = null
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Initialize with persistence manager
     */
    fun initialize(persistence: ChatPersistenceManager) {
        persistenceManager = persistence
        android.util.Log.d("RoomStore", "✅ RoomStore initialized with ChatPersistenceManager")
    }
    
    /**
     * Load rooms from persistence
     */
    suspend fun loadRoomsFromPersistence(): List<Room> {
        return try {
            val persistence = persistenceManager
            if (persistence != null) {
                val persistedRooms = persistence.loadRooms()
                android.util.Log.d("RoomStore", "📂 Loaded ${persistedRooms.size} rooms from persistence")
                persistedRooms
            } else {
                android.util.Log.w("RoomStore", "⚠️ ChatPersistenceManager not initialized, cannot load from persistence")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomStore", "❌ Error loading rooms from persistence", e)
            emptyList()
        }
    }
    
    /**
     * Load current room JID from persistence
     */
    suspend fun loadCurrentRoomJidFromPersistence(): String? {
        return try {
            val persistence = persistenceManager
            if (persistence != null) {
                persistence.loadCurrentRoomJid()
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomStore", "❌ Error loading current room JID from persistence", e)
            null
        }
    }
    
    /**
     * Persist rooms (background, non-blocking)
     */
    private fun persistRooms() {
        val persistence = persistenceManager ?: return
        persistenceScope.launch {
            try {
                persistence.saveRooms(_rooms.value)
            } catch (e: Exception) {
                android.util.Log.e("RoomStore", "❌ Error persisting rooms", e)
            }
        }
    }
    
    /**
     * Persist current room JID (background, non-blocking)
     */
    private fun persistCurrentRoomJid() {
        val persistence = persistenceManager ?: return
        persistenceScope.launch {
            try {
                persistence.saveCurrentRoomJid(_currentRoom.value?.jid)
            } catch (e: Exception) {
                android.util.Log.e("RoomStore", "❌ Error persisting current room JID", e)
            }
        }
    }

    /**
     * Set rooms
     */
    fun setRooms(rooms: List<Room>) {
        val existingByJid = _rooms.value.associateBy { it.jid }
        _rooms.value = rooms.map { room ->
            mergeSingleRoomPlaceholder(room, existingByJid[room.jid])
        }
        // Persist rooms (background)
        persistRooms()
    }

    /**
     * Add room
     */
    fun addRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val existingIndex = currentRooms.indexOfFirst { it.id == room.id || it.jid == room.jid }
        if (existingIndex == -1) {
            currentRooms.add(room)
        } else {
            currentRooms[existingIndex] = mergeSingleRoomPlaceholder(room, currentRooms[existingIndex])
        }
        _rooms.value = currentRooms
        persistRooms()
    }

    /**
     * Authoritative update — trusts the incoming [room] as-is (including
     * intentional zero counters). Does NOT run through
     * `mergeSingleRoomPlaceholder`; that helper is only for API-sourced
     * rooms and would revert zero unread/pending back to stale values.
     * `presenceReady` is sticky.
     */
    fun updateRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val index = currentRooms.indexOfFirst { it.id == room.id || it.jid == room.jid }
        if (index >= 0) {
            val existing = currentRooms[index]
            val next = if (room.presenceReady || !existing.presenceReady) {
                room
            } else {
                room.copy(presenceReady = true)
            }
            currentRooms[index] = next
            _rooms.value = currentRooms
            persistRooms()
            if (_currentRoom.value?.id == room.id || _currentRoom.value?.jid == room.jid) {
                _currentRoom.value = next
                persistCurrentRoomJid()
            }
            return
        }
        if (_currentRoom.value?.id == room.id || _currentRoom.value?.jid == room.jid) {
            _currentRoom.value = room
            persistCurrentRoomJid()
        }
    }

    /**
     * Remove room
     */
    fun removeRoom(roomId: String) {
        val currentRooms = _rooms.value.toMutableList()
        currentRooms.removeAll { it.id == roomId || it.jid == roomId }
        _rooms.value = currentRooms
        // Persist rooms (background)
        persistRooms()
        if (_currentRoom.value?.id == roomId || _currentRoom.value?.jid == roomId) {
            _currentRoom.value = null
            // Persist current room JID (background)
            persistCurrentRoomJid()
        }
    }

    /**
     * Set current room. Also recomputes unread counts — web's unreadMiddleware
     * listens for `setCurrentRoom` and zeroes the new-active room's counter
     * immediately. Without this, the room the user just opened would keep its
     * stale unread badge until the next message arrived to trigger a recompute.
     */
    fun setCurrentRoom(room: Room?) {
        val prev = _currentRoom.value

        // While a room is open ChatRoomView keeps lastViewedTimestamp=0.
        // Bump to "now" before recomputing so a list-switch doesn't flash a
        // full badge on the outgoing room.
        if (prev != null && prev.jid != room?.jid && (prev.lastViewedTimestamp ?: 0L) <= 0L) {
            val now = System.currentTimeMillis()
            updateRoom(prev.copy(lastViewedTimestamp = now))
        }

        _currentRoom.value = room
        persistCurrentRoomJid()

        if (room != null) {
            val messagesForRoom = com.ethora.chat.core.store.MessageStore.getMessagesForRoom(room.jid)
            updateUnreadCount(room.jid, messagesForRoom)
        }
        if (prev != null && prev.jid != room?.jid) {
            val messagesForPrev = com.ethora.chat.core.store.MessageStore.getMessagesForRoom(prev.jid)
            updateUnreadCount(prev.jid, messagesForPrev)
        }

        // Strip delimiter on new active, (maybe) insert on backgrounded prev.
        room?.jid?.let { com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(it) }
        if (prev != null && prev.jid != room?.jid) {
            com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(prev.jid)
        }
    }

    /**
     * Get room by ID
     */
    fun getRoomById(roomId: String): Room? {
        return _rooms.value.firstOrNull { it.id == roomId }
    }
    
    /**
     * Get room by JID
     */
    fun getRoomByJid(roomJid: String): Room? {
        return _rooms.value.firstOrNull { it.jid == roomJid }
    }

    fun upsertRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val index = currentRooms.indexOfFirst { it.jid == room.jid || it.id == room.id }
        if (index >= 0) {
            currentRooms[index] = mergeSingleRoomPlaceholder(room, currentRooms[index])
        } else {
            currentRooms.add(room)
        }
        _rooms.value = currentRooms
        persistRooms()
    }
    
    /**
     * Set loading state for a specific room (matches web: setIsLoading({ chatJID, loading }))
     */
    fun setRoomLoading(roomJid: String, loading: Boolean) {
        val currentStates = _roomLoadingStates.value.toMutableMap()
        currentStates[roomJid] = loading
        _roomLoadingStates.value = currentStates
        setHistoryPreloadState(
            roomJid,
            if (loading) HistoryPreloadState.LOADING else HistoryPreloadState.IDLE
        )
    }
    
    /**
     * Get loading state for a specific room
     */
    fun isRoomLoading(roomJid: String): Boolean {
        return _roomLoadingStates.value[roomJid] ?: false
    }
    
    /**
     * Set global loading state (matches web: setIsLoading({ loading }))
     */
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    /**
     * Set composing state for a room (matches web: setComposing)
     * Tracks multiple users typing by adding/removing them from the composingList
     * Auto-clears typing indicators after 5 seconds if no update received
     */
    fun setComposing(roomJid: String, isComposing: Boolean, composingList: List<String>) {
        val room = getRoomByJid(roomJid)
        room?.let {
            // Get the current composing list
            val currentList = it.composingList?.toMutableList() ?: mutableListOf()
            
            // Ensure room has an entry in typingTimeouts map
            if (!typingTimeouts.containsKey(roomJid)) {
                typingTimeouts[roomJid] = mutableMapOf()
            }
            
            // Add or remove users from the list
            composingList.forEach { userName ->
                if (isComposing) {
                    // Add user if not already in the list
                    if (!currentList.contains(userName)) {
                        currentList.add(userName)
                    }
                    
                    // Cancel existing timeout for this user
                    typingTimeouts[roomJid]?.get(userName)?.cancel()
                    
                    // Start a new timeout to auto-remove this user after 5 seconds
                    val timeoutJob = persistenceScope.launch {
                        delay(typingTimeoutDuration)
                        // Auto-remove user from typing list after timeout
                        setComposing(roomJid, false, listOf(userName))
                        android.util.Log.d("RoomStore", "⏱️ Auto-cleared typing indicator for $userName in $roomJid")
                    }
                    typingTimeouts[roomJid]?.put(userName, timeoutJob)
                    
                } else {
                    // Remove user from the list
                    currentList.remove(userName)
                    
                    // Cancel the timeout for this user
                    typingTimeouts[roomJid]?.get(userName)?.cancel()
                    typingTimeouts[roomJid]?.remove(userName)
                }
            }
            
            // Update the room with the new composing state
            val updatedRoom = it.copy(
                composing = currentList.isNotEmpty(),
                composingList = currentList.toList()
            )
            updateRoom(updatedRoom)
        }
    }
    
    /**
     * Set last viewed timestamp for a room (matches web: setLastViewedTimestamp)
     * When a room is opened, set to 0 to mark all messages as read
     * When a room is closed, set to current timestamp
     */
    fun setLastViewedTimestamp(roomJid: String, timestamp: Long) {
        val room = getRoomByJid(roomJid)
        room?.let {
            // Reset BOTH unreadMessages and unreadCapped so the per-room dot
            // AND the "10+" flag disappear atomically. Web zeroes both via
            // the unreadMiddleware setLastViewedTimestamp action.
            val updatedRoom = it.copy(
                lastViewedTimestamp = timestamp,
                unreadMessages = 0,
                unreadCapped = false
            )
            updateRoom(updatedRoom)
            com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(roomJid)
            android.util.Log.d("RoomStore", "📅 lastViewedTimestamp=$timestamp + unread zeroed room=$roomJid")
        }
    }
    
    /**
     * Update pending messages count for a room
     */
    fun updatePendingCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid)
        if (room != null) {
            val pendingCount = messages.count { it.pending == true }
            if (room.pendingMessages != pendingCount) {
                val updatedRoom = room.copy(pendingMessages = pendingCount)
                updateRoom(updatedRoom)
                android.util.Log.d("RoomStore", "📊 Updated pending count for $roomJid: $pendingCount")
            }
        }
    }
    
    /**
     * Calculate and update unread messages count for a room.
     * One-for-one port of `web/src/roomStore/Middleware/unreadMidlleware.tsx`
     * `computeUnreadForRoom`:
     *   • Active room → always 0 (the user is looking at it).
     *   • Non-active + lastViewed missing-or-zero → EVERY countable message is
     *     unread (fresh room, never opened). Previous Android logic treated
     *     this branch as "all read" which is why the chat-tab badge went to 0
     *     immediately and the per-room dot never appeared on first app open.
     *   • Non-active + lastViewed > 0 → count countable messages whose
     *     timestamp > lastViewed.
     * "Countable" excludes: delimiter-new, pending, system, and messages from
     * the current user.
     */
    fun updateUnreadCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid) ?: return
        val activeJid = _currentRoom.value?.jid

        // Active room → always 0 unread
        if (roomJid == activeJid) {
            if (room.unreadMessages != 0 || room.unreadCapped) {
                updateRoom(room.copy(unreadMessages = 0, unreadCapped = false))
            }
            return
        }

        val currentUserXmppUsername = UserStore.currentUser.value?.xmppUsername
        val userLocal = currentUserXmppUsername?.substringBefore("@")?.takeIf { it.isNotBlank() }
        val lastViewed = room.lastViewedTimestamp ?: 0L

        val countable = messages.count { msg ->
            if (msg.id == "delimiter-new") return@count false
            if (msg.pending == true) return@count false
            if (msg.isSystemMessage == "true") return@count false

            // Skip own messages (web: toLocal(msg.user.id) === toLocal(currentXmppUsername))
            val msgLocal = msg.user.id.substringBefore("@").takeIf { it.isNotBlank() }
            if (userLocal != null && msgLocal != null &&
                msgLocal.equals(userLocal, ignoreCase = true)) return@count false

            val ts = msg.timestamp ?: msg.date.time
            if (ts <= 0) return@count false
            if (lastViewed <= 0) return@count true
            ts > lastViewed
        }

        val unread = countable.coerceAtMost(MAX_UNREAD_COUNT)
        val capped = countable > MAX_UNREAD_COUNT

        if ((room.unreadMessages) != unread || room.unreadCapped != capped) {
            updateRoom(room.copy(unreadMessages = unread, unreadCapped = capped))
            android.util.Log.d("RoomStore", "📊 Unread count for $roomJid: $unread (capped=$capped)")
        }
    }

    fun setHistoryPreloadState(roomJid: String, state: HistoryPreloadState) {
        val room = getRoomByJid(roomJid) ?: return
        if (room.historyPreloadState == state) return
        updateRoom(room.copy(historyPreloadState = state))
    }
    
    /**
     * Clear all rooms
     */
    fun clear() {
        _rooms.value = emptyList()
        _currentRoom.value = null
        _roomLoadingStates.value = emptyMap()
        _isLoading.value = false
        // Persist cleared state (background)
        persistenceScope.launch {
            persistenceManager?.saveRooms(emptyList())
            persistenceManager?.saveCurrentRoomJid(null)
        }
    }
    
    /**
     * Get persistence manager (for LogoutService)
     */
    internal fun getPersistenceManager(): ChatPersistenceManager? {
        return persistenceManager
    }
}
