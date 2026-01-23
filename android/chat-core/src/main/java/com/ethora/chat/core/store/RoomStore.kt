package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Room store for managing rooms state
 */
object RoomStore {
    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _currentRoom = MutableStateFlow<Room?>(null)
    val currentRoom: StateFlow<Room?> = _currentRoom.asStateFlow()

    /**
     * Set rooms
     */
    fun setRooms(rooms: List<Room>) {
        _rooms.value = rooms
    }

    /**
     * Add room
     */
    fun addRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        if (!currentRooms.any { it.id == room.id }) {
            currentRooms.add(room)
            _rooms.value = currentRooms
        }
    }

    /**
     * Update room
     */
    fun updateRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val index = currentRooms.indexOfFirst { it.id == room.id }
        if (index >= 0) {
            currentRooms[index] = room
            _rooms.value = currentRooms
        }
        if (_currentRoom.value?.id == room.id) {
            _currentRoom.value = room
        }
    }

    /**
     * Remove room
     */
    fun removeRoom(roomId: String) {
        val currentRooms = _rooms.value.toMutableList()
        currentRooms.removeAll { it.id == roomId }
        _rooms.value = currentRooms
        if (_currentRoom.value?.id == roomId) {
            _currentRoom.value = null
        }
    }

    /**
     * Set current room
     */
    fun setCurrentRoom(room: Room?) {
        _currentRoom.value = room
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
     * Clear all rooms
     */
    fun clear() {
        _rooms.value = emptyList()
        _currentRoom.value = null
    }
}
