package com.ethora.chat.ui.components

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.createRoomFromApi
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for room list
 */
class RoomListViewModel : ViewModel() {
    private val TAG = "RoomListViewModel"
    
    val rooms: StateFlow<List<Room>> = RoomStore.rooms
    val isLoading: StateFlow<Boolean> = RoomStore.isLoading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    private val _createdRoom = MutableStateFlow<Room?>(null)
    val createdRoom: StateFlow<Room?> = _createdRoom.asStateFlow()

    init {
        // Don't load rooms here - they should be loaded globally in EthoraChat.kt
        // This matches web behavior where rooms are loaded once in useChatWrapperInit
        // and RoomListViewModel just observes the existing rooms
        Log.d(TAG, "RoomListViewModel initialized - using existing rooms from RoomStore")
    }

    /**
     * Load rooms
     */
    fun loadRooms() {
        viewModelScope.launch {
            RoomStore.setLoading(true)
            _error.value = null
            try {
                val roomsList = RoomsAPIHelper.getRooms()
                RoomStore.setRooms(roomsList)
                Log.d(TAG, "Loaded ${roomsList.size} rooms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load rooms", e)
                _error.value = "Failed to load chats: ${e.message}"
            } finally {
                RoomStore.setLoading(false)
            }
        }
    }
    
    /**
     * Create a new room (matches web NewChatModal.handleCreateRoom)
     */
    fun createRoom(
        title: String,
        type: RoomType,
        description: String? = null,
        picturePath: String? = null,
        members: List<String>? = null
    ) {
        viewModelScope.launch {
            RoomStore.setLoading(true)
            _error.value = null
            _toastMessage.value = "Room is being created..."
            
            try {
                Log.d(TAG, "Creating room: $title (type: $type)")
                
                // Step 1: Upload image if provided (matches web: uploadFile first)
                var imageLocation: String? = null
                if (picturePath != null) {
                    try {
                        val pictureFile = File(picturePath)
                        if (pictureFile.exists()) {
                            val mimeType = getMimeType(pictureFile.name)
                            val token = UserStore.currentUser.value?.token
                            if (token != null) {
                                val baseUrl = AppConfig.defaultBaseURL
                                val uploadResult = AuthAPIHelper.uploadFile(
                                    file = pictureFile,
                                    mimeType = mimeType,
                                    token = token,
                                    baseUrl = baseUrl
                                )
                                imageLocation = uploadResult?.location
                                Log.d(TAG, "Image uploaded: $imageLocation")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to upload image", e)
                        // Continue without image
                    }
                }
                
                // Step 2: Create room via API (matches web: postRoom)
                val apiRoom = RoomsAPIHelper.createRoom(
                    title = title,
                    type = type,
                    description = description ?: "No description",
                    picture = imageLocation ?: "",
                    members = members
                )
                
                // Step 3: Convert to Room and add to store (matches web: handleRoomCreation)
                val newRoom = createRoomFromApi(
                    apiRoom = apiRoom,
                    conferenceDomain = AppConfig.defaultConferenceDomain,
                    usersArrayLength = members?.size ?: 0
                )
                
                // Add to existing rooms at the beginning (matches web: addRoomViaApi)
                val currentRooms = RoomStore.rooms.value.toMutableList()
                if (!currentRooms.any { it.id == newRoom.id || it.jid == newRoom.jid }) {
                    currentRooms.add(0, newRoom) // Add at the beginning
                    RoomStore.setRooms(currentRooms)
                }
                
                // Set as current room (matches web: setCurrentRoom)
                RoomStore.setCurrentRoom(newRoom)
                _createdRoom.value = newRoom
                
                _toastMessage.value = "Room created successfully!"
                Log.d(TAG, "Room created successfully: ${newRoom.id}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create room", e)
                _error.value = "Failed to create chat: ${e.message}"
                _toastMessage.value = "Failed to create chat: ${e.message}"
            } finally {
                RoomStore.setLoading(false)
            }
        }
    }
    
    /**
     * Get MIME type from file extension
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg" // Default
        }
    }
    
    /**
     * Clear created room (after navigation)
     */
    fun clearCreatedRoom() {
        _createdRoom.value = null
    }
    
    /**
     * Clear toast message
     */
    fun clearToast() {
        _toastMessage.value = null
    }
}
