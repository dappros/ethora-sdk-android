package com.ethora.chat.ui.components

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.createRoomFromApi
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.store.RoomStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for room list
 */
class RoomListViewModel : ViewModel() {
    private val TAG = "RoomListViewModel"
    
    val rooms: StateFlow<List<Room>> = RoomStore.rooms

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadRooms()
    }

    /**
     * Load rooms
     */
    fun loadRooms() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val roomsList = RoomsAPIHelper.getRooms()
                RoomStore.setRooms(roomsList)
                Log.d(TAG, "Loaded ${roomsList.size} rooms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load rooms", e)
                _error.value = "Failed to load chats: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Create a new room
     */
    fun createRoom(
        title: String,
        type: RoomType,
        description: String? = null,
        members: List<String>? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                Log.d(TAG, "Creating room: $title (type: $type)")
                val apiRoom = RoomsAPIHelper.createRoom(
                    title = title,
                    type = type,
                    description = description,
                    members = members
                )
                
                // Convert to Room and add to store
                val newRoom = createRoomFromApi(
                    apiRoom = apiRoom,
                    conferenceDomain = AppConfig.defaultConferenceDomain
                )
                
                // Add to existing rooms
                val currentRooms = RoomStore.rooms.value.toMutableList()
                currentRooms.add(0, newRoom) // Add at the beginning
                RoomStore.setRooms(currentRooms)
                
                Log.d(TAG, "Room created successfully: ${newRoom.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create room", e)
                _error.value = "Failed to create chat: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
