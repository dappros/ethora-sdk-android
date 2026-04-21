package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.HistoryPreloadState
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
        _rooms.value = rooms
        // Persist rooms (background)
        persistRooms()
    }

    /**
     * Add room
     */
    fun addRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        if (!currentRooms.any { it.id == room.id || it.jid == room.jid }) {
            currentRooms.add(room)
            _rooms.value = currentRooms
            // Persist rooms (background)
            persistRooms()
        }
    }

    /**
     * Update room
     */
    fun updateRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val index = currentRooms.indexOfFirst { it.id == room.id || it.jid == room.jid }
        if (index >= 0) {
            currentRooms[index] = room
            _rooms.value = currentRooms
            // Persist rooms (background)
            persistRooms()
        }
        if (_currentRoom.value?.id == room.id || _currentRoom.value?.jid == room.jid) {
            _currentRoom.value = room
            // Persist current room JID (background)
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
     * Set current room
     */
    fun setCurrentRoom(room: Room?) {
        _currentRoom.value = room
        // Persist current room JID (background)
        persistCurrentRoomJid()
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
            val updatedRoom = it.copy(
                lastViewedTimestamp = timestamp,
                unreadMessages = 0 // Reset unread count when timestamp is updated
            )
            updateRoom(updatedRoom)
            android.util.Log.d("RoomStore", "📅 Set lastViewedTimestamp for $roomJid: $timestamp")
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
     * Calculate and update unread messages count for a room
     * Matches web: unreadMiddleware logic
     */
    fun updateUnreadCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid)
        val currentRoom = _currentRoom.value
        
        // Don't count unread for the currently active room
        if (room != null && roomJid != currentRoom?.jid && room.lastViewedTimestamp != null && room.lastViewedTimestamp != 0L) {
            val unreadCountRaw = messages.count { message ->
                // Exclude delimiter messages
                message.id != "delimiter-new" &&
                // Count messages newer than lastViewedTimestamp
                (message.timestamp ?: message.date.time) > room.lastViewedTimestamp!!
            }
            val unreadCount = unreadCountRaw.coerceAtMost(MAX_UNREAD_COUNT)
            val isUnreadCapped = unreadCountRaw > MAX_UNREAD_COUNT
            
            if (room.unreadMessages != unreadCount || room.unreadCapped != isUnreadCapped) {
                val updatedRoom = room.copy(
                    unreadMessages = unreadCount,
                    unreadCapped = isUnreadCapped
                )
                updateRoom(updatedRoom)
                android.util.Log.d("RoomStore", "📊 Updated unread count for $roomJid: $unreadCount")
            }
        } else if (room != null && (room.lastViewedTimestamp == null || room.lastViewedTimestamp == 0L)) {
            // If lastViewedTimestamp is 0 or null, all messages are read
            if (room.unreadMessages != 0 || room.unreadCapped) {
                val updatedRoom = room.copy(unreadMessages = 0, unreadCapped = false)
                updateRoom(updatedRoom)
            }
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
