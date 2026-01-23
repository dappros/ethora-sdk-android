package com.ethora.chat.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for chat room
 */
class ChatRoomViewModel(
    private val room: Room,
    private val xmppClient: XMPPClient?
) : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
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

    init {
        // Observe messages from store
        viewModelScope.launch {
            MessageStore.messages
                .map { 
                    val roomMessages = it[room.jid] ?: emptyList()
                    android.util.Log.d("ChatRoomViewModel", "🔄 Store updated, messages for ${room.jid}: ${roomMessages.size}")
                    roomMessages
                }
                .distinctUntilChanged() // Only update if messages actually changed
                .collect { 
                    android.util.Log.d("ChatRoomViewModel", "📥 Collected ${it.size} messages from store")
                    if (it.isNotEmpty() || _messages.value.isEmpty()) {
                        _messages.value = it
                        android.util.Log.d("ChatRoomViewModel", "✅ Updated _messages to ${_messages.value.size}")
                    }
                }
        }
        
        // Load message history when entering room
        loadMessages()
    }

    /**
     * Send message
     */
    fun sendMessage(text: String) {
        viewModelScope.launch {
            val success = xmppClient?.sendMessage(room.jid, text) ?: false
            if (success) {
                // Message will be added via XMPP message handler
                // For now, create optimistic message
                val currentUser = UserStore.currentUser.value
                if (currentUser != null) {
                    val optimisticMessage = Message(
                        id = "temp_${System.currentTimeMillis()}",
                        body = text,
                        user = currentUser,
                        date = java.util.Date(),
                        roomJid = room.jid
                    )
                    MessageStore.addMessage(room.jid, optimisticMessage)
                }
            }
        }
    }

    /**
     * Send media
     */
    fun sendMedia(data: ByteArray, mimeType: String) {
        // Implementation for media sending
    }

    /**
     * Load messages (initial load)
     */
    fun loadMessages() {
        viewModelScope.launch {
            try {
                android.util.Log.d("ChatRoomViewModel", "📥 Loading messages for room: ${room.jid}")
                
                // First check if we have messages in store
                val storedMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                android.util.Log.d("ChatRoomViewModel", "   Stored messages: ${storedMessages.size}")
                
                if (storedMessages.isNotEmpty()) {
                    // We have messages, show them immediately without loading indicator
                    _messages.value = storedMessages
                    _isLoading.value = false
                    android.util.Log.d("ChatRoomViewModel", "   ✅ Set stored messages to UI (${storedMessages.size} messages)")
                    
                    // Still try to refresh in background (without showing loader)
                    refreshMessagesInBackground()
                } else {
                    // No messages in store, show loader and fetch
                    _isLoading.value = true
                    _hasMoreMessages.value = true
                    
                    // Load history from XMPP if client is available
                    xmppClient?.let { client ->
                        android.util.Log.d("ChatRoomViewModel", "   Requesting history from XMPP...")
                        val history = client.getHistory(room.jid, max = 30)
                        android.util.Log.d("ChatRoomViewModel", "   Received ${history.size} messages from history")
                        
                        if (history.isNotEmpty()) {
                            // Add to message store - this will trigger the flow observer and update last message
                            MessageStore.addMessages(room.jid, history)
                            android.util.Log.d("ChatRoomViewModel", "   ✅ Added ${history.size} messages to store")
                            
                            // Update hasMoreMessages based on result
                            _hasMoreMessages.value = history.size >= 30
                            
                            // Force update UI immediately - don't wait for flow
                            kotlinx.coroutines.delay(50) // Small delay to ensure store is updated
                            val updatedMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                            if (updatedMessages.isNotEmpty()) {
                                _messages.value = updatedMessages
                                android.util.Log.d("ChatRoomViewModel", "   ✅ Updated UI with ${updatedMessages.size} messages")
                            } else {
                                android.util.Log.w("ChatRoomViewModel", "   ⚠️ Store is empty after adding messages!")
                            }
                        } else {
                            android.util.Log.w("ChatRoomViewModel", "   ⚠️ History is empty")
                            _hasMoreMessages.value = false
                        }
                    } ?: run {
                        android.util.Log.w("ChatRoomViewModel", "   ⚠️ XMPP client is null")
                    }
                    
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error loading messages", e)
                _isLoading.value = false
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
                    val history = client.getHistory(room.jid, max = 30)
                    if (history.isNotEmpty()) {
                        // Add messages - this will automatically update last message
                        MessageStore.addMessages(room.jid, history)
                        _hasMoreMessages.value = history.size >= 30
                        android.util.Log.d("ChatRoomViewModel", "   🔄 Refreshed ${history.size} messages in background")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error refreshing messages", e)
            }
        }
    }
    
    /**
     * Load more messages (pagination) when scrolling to top
     * In reverse layout, "top" means oldest messages (first in the list)
     */
    fun loadMoreMessages() {
        val currentHasMore = _hasMoreMessages.value
        if (isLoadingMoreInProgress || !currentHasMore || _isLoading.value) {
            android.util.Log.d("ChatRoomViewModel", "⏭️ Skipping loadMore: isLoadingMore=$isLoadingMoreInProgress, hasMore=$currentHasMore, isLoading=${_isLoading.value}")
            return
        }
        
        viewModelScope.launch {
            isLoadingMoreInProgress = true
            _isLoadingMore.value = true
            try {
                val currentMessages = _messages.value
                if (currentMessages.isEmpty()) {
                    android.util.Log.d("ChatRoomViewModel", "⏭️ No messages to paginate from")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }
                
                // In reverse layout, first message in list is the oldest (at "top")
                // Get the first message (oldest) for pagination
                val oldestMessage = currentMessages.firstOrNull()
                if (oldestMessage == null) {
                    android.util.Log.d("ChatRoomViewModel", "⏭️ No oldest message found")
                    isLoadingMoreInProgress = false
                    _isLoadingMore.value = false
                    return@launch
                }
                
                val beforeTimestamp = oldestMessage.timestamp ?: oldestMessage.date.time
                
                android.util.Log.d("ChatRoomViewModel", "📜 Loading more messages before timestamp: $beforeTimestamp (oldest message: ${oldestMessage.id})")
                
                xmppClient?.let { client ->
                    val history = client.getHistory(room.jid, max = 30, before = beforeTimestamp)
                    android.util.Log.d("ChatRoomViewModel", "   Received ${history.size} older messages")
                    
                    if (history.isNotEmpty()) {
                        // Add to message store - these will be older messages
                        MessageStore.addMessages(room.jid, history)
                        android.util.Log.d("ChatRoomViewModel", "   ✅ Added ${history.size} older messages to store")
                        
                        // Update hasMoreMessages - if we got less than max, we've reached the end
                        val hasMore = history.size >= 30
                        _hasMoreMessages.value = hasMore
                        
                        // Force UI update to show new messages
                        kotlinx.coroutines.delay(50)
                        val updatedMessages = MessageStore.messages.value[room.jid] ?: emptyList()
                        _messages.value = updatedMessages
                        android.util.Log.d("ChatRoomViewModel", "   ✅ Updated UI with ${updatedMessages.size} total messages")
                        
                        if (!hasMore) {
                            android.util.Log.d("ChatRoomViewModel", "   ✅ Reached end of history (got ${history.size} < 30)")
                        }
                    } else {
                        android.util.Log.d("ChatRoomViewModel", "   ⚠️ No more messages available")
                        _hasMoreMessages.value = false
                    }
                } ?: run {
                    android.util.Log.w("ChatRoomViewModel", "   ⚠️ XMPP client is null")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRoomViewModel", "❌ Error loading more messages", e)
            } finally {
                isLoadingMoreInProgress = false
                _isLoadingMore.value = false
                android.util.Log.d("ChatRoomViewModel", "   Finished loading more, isLoadingMore: false")
            }
        }
    }
}
