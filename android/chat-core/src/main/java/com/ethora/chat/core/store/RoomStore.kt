package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.persistence.ChatPersistenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Room store for managing rooms state
 * Matches web Redux store pattern
 * Persists rooms to DataStore (matches web: redux-persist with localStorage)
 */
object RoomStore {
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
     */
    fun setComposing(roomJid: String, isComposing: Boolean, composingList: List<String>) {
        val room = getRoomByJid(roomJid)
        room?.let {
            val updatedRoom = it.copy(
                composing = isComposing,
                composingList = if (isComposing) composingList else emptyList()
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
     * Calculate and update unread messages count for a room
     * Matches web: unreadMiddleware logic
     */
    fun updateUnreadCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid)
        val currentRoom = _currentRoom.value
        
        // Don't count unread for the currently active room
        if (room != null && roomJid != currentRoom?.jid && room.lastViewedTimestamp != null && room.lastViewedTimestamp != 0L) {
            val unreadCount = messages.count { message ->
                // Exclude delimiter messages
                message.id != "delimiter-new" &&
                // Count messages newer than lastViewedTimestamp
                (message.timestamp ?: message.date.time) > room.lastViewedTimestamp!!
            }
            
            if (room.unreadMessages != unreadCount) {
                val updatedRoom = room.copy(unreadMessages = unreadCount)
                updateRoom(updatedRoom)
                android.util.Log.d("RoomStore", "📊 Updated unread count for $roomJid: $unreadCount")
            }
        } else if (room != null && (room.lastViewedTimestamp == null || room.lastViewedTimestamp == 0L)) {
            // If lastViewedTimestamp is 0 or null, all messages are read
            if (room.unreadMessages != 0) {
                val updatedRoom = room.copy(unreadMessages = 0)
                updateRoom(updatedRoom)
            }
        }
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
