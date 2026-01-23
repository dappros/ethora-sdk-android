package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient

/**
 * Chat room view
 */
@Composable
fun ChatRoomView(
    room: Room,
    xmppClient: XMPPClient?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = remember { ChatRoomViewModel(room, xmppClient) }
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsState()
    val composingUsers by viewModel.composingUsers.collectAsState()
    
    // Get saved scroll position or default to 0 (bottom in reverse layout)
    val savedScrollPosition = remember(room.jid) { 
        ScrollPositionStore.getScrollPosition(room.jid) 
    }
    
    // LazyListState for scroll detection - restore saved position or start at bottom
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition ?: 0
    )
    
    val density = LocalDensity.current
    val scrollThresholdPx = with(density) { 150.dp.toPx() }
    
    // Debug logging
    LaunchedEffect(messages.size, isLoading) {
        android.util.Log.d("ChatRoomView", "📱 Messages in UI: ${messages.size}, isLoading: $isLoading")
        if (messages.isEmpty() && !isLoading) {
            android.util.Log.w("ChatRoomView", "⚠️ No messages displayed but not loading!")
        }
    }
    
    // Log message details for debugging
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            android.util.Log.d("ChatRoomView", "📱 First message: ${messages.first().body.take(50)}, Last: ${messages.last().body.take(50)}")
        }
    }
    
    // Auto-scroll to bottom when entering chat (if no saved position)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (savedScrollPosition == null) {
                // No saved position, scroll to bottom (index 0 in reverse layout = newest messages at bottom)
                kotlinx.coroutines.delay(100) // Small delay to ensure layout is ready
                listState.animateScrollToItem(0)
                android.util.Log.d("ChatRoomView", "📍 Auto-scrolled to bottom (newest messages)")
            } else {
                // Restore saved position
                kotlinx.coroutines.delay(100)
                listState.scrollToItem(savedScrollPosition)
                android.util.Log.d("ChatRoomView", "📍 Restored scroll position: $savedScrollPosition")
            }
        }
    }
    
    // Save scroll position when navigating back
    DisposableEffect(room.jid) {
        onDispose {
            val currentIndex = listState.firstVisibleItemIndex
            ScrollPositionStore.saveScrollPosition(room.jid, currentIndex)
            android.util.Log.d("ChatRoomView", "💾 Saved scroll position on dispose: $currentIndex")
        }
    }
    
    // Detect scroll to top with 150px threshold (for reverse layout, top = oldest messages)
    // In reverse layout, scrolling from bottom to top means scrolling up to older messages
    LaunchedEffect(listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex, listState.layoutInfo, isLoadingMore, isLoading, hasMoreMessages) {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isEmpty() || messages.isEmpty()) return@LaunchedEffect
        
        val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull() ?: return@LaunchedEffect
        val firstVisibleIndex = firstVisibleItem.index
        val scrollOffset = listState.firstVisibleItemScrollOffset
        
        // In reverse layout, index 0 is the oldest message (at the "top")
        // When scrolling from bottom to top, we're moving towards index 0
        // Calculate distance from top using actual item size
        val distanceFromTop = if (firstVisibleIndex == 0) {
            // We're at the top item, use the scroll offset
            scrollOffset.toFloat()
        } else {
            // Calculate distance: sum of heights of items before firstVisibleIndex + scrollOffset
            var totalHeight = 0f
            for (i in 0 until firstVisibleIndex) {
                val item = layoutInfo.visibleItemsInfo.find { it.index == i }
                if (item != null) {
                    totalHeight += item.size.toFloat()
                } else {
                    // Estimate if item not visible (roughly 80px per message)
                    totalHeight += 80f
                }
            }
            totalHeight + scrollOffset.toFloat()
        }
        
        android.util.Log.d("ChatRoomView", "📜 Scroll: index=$firstVisibleIndex, offset=$scrollOffset, distanceFromTop=$distanceFromTop, threshold=$scrollThresholdPx")
        
        // Load more when scrolled within 150px of the top (oldest messages)
        // Only trigger if we have messages, aren't already loading, and have more messages available
        if (distanceFromTop <= scrollThresholdPx && !isLoadingMore && !isLoading && hasMoreMessages) {
            android.util.Log.d("ChatRoomView", "📜 Within threshold (${distanceFromTop}px <= ${scrollThresholdPx}px), triggering loadMore...")
            viewModel.loadMoreMessages()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        ChatRoomHeader(
            room = room,
            onBack = {
                // Save scroll position before navigating back
                val currentIndex = listState.firstVisibleItemIndex
                ScrollPositionStore.saveScrollPosition(room.jid, currentIndex)
                android.util.Log.d("ChatRoomView", "💾 Saved scroll position on back: $currentIndex")
                onBack()
            }
        )
        
        // Messages list
        Box(modifier = Modifier.weight(1f)) {
            when {
                messages.isNotEmpty() -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Loading indicator above the list - only when loading more
                        if (isLoadingMore) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        
                        // Show messages
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            reverseLayout = true
                        ) {
                        
                        items(
                            count = messages.size,
                            key = { index -> 
                                val reversedIndex = messages.size - 1 - index
                                messages.getOrNull(reversedIndex)?.id ?: "item_$index"
                            }
                        ) { index ->
                            val reversedIndex = messages.size - 1 - index
                            val message = messages.getOrNull(reversedIndex)
                            
                            if (message == null) {
                                android.util.Log.e("ChatRoomView", "❌ Message is null at index $index (reversed: $reversedIndex)")
                                return@items
                            }
                            
                            // Check if this message is from the current user
                            // Compare by checking if senderJID/xmppUsername contains the current user's xmppUsername
                            val currentUserXmppUsername = viewModel.currentUserXmppUsername
                            val messageXmppUsername = message.user.xmppUsername
                            val messageUserJID = message.user.userJID
                            
                            val isUser = when {
                                // First check: if current user's xmppUsername is not null, check if message's xmppUsername or userJID contains it
                                !currentUserXmppUsername.isNullOrBlank() -> {
                                    val containsXmppUsername = !messageXmppUsername.isNullOrBlank() && 
                                            messageXmppUsername.contains(currentUserXmppUsername, ignoreCase = false)
                                    val containsUserJID = !messageUserJID.isNullOrBlank() && 
                                            messageUserJID.contains(currentUserXmppUsername, ignoreCase = false)
                                    containsXmppUsername || containsUserJID
                                }
                                // Fallback: compare by user ID
                                else -> {
                                    val currentUserId = viewModel.currentUserId
                                    val messageUserId = message.user.id
                                    !currentUserId.isNullOrBlank() && !messageUserId.isNullOrBlank() && 
                                            currentUserId == messageUserId
                                }
                            }
                            
                            android.util.Log.d("ChatRoomView", "   Message from: xmppUsername=$messageXmppUsername, userJID=$messageUserJID, isUser=$isUser (current: xmppUsername=$currentUserXmppUsername)")
                            
                            // Determine if this is the first message in a group
                            // Group messages from the same user that are within 5 minutes of each other
                            val prevMessage = if (reversedIndex > 0) messages[reversedIndex - 1] else null
                            val nextMessage = if (reversedIndex < messages.size - 1) messages[reversedIndex + 1] else null
                            
                            val isFirstInGroup = when {
                                // First message overall
                                prevMessage == null -> true
                                // Different user
                                prevMessage.user.id != message.user.id -> true
                                // More than 5 minutes apart
                                else -> {
                                    val timeDiff = (message.timestamp ?: message.date.time) - (prevMessage.timestamp ?: prevMessage.date.time)
                                    timeDiff > 5 * 60 * 1000 // 5 minutes in milliseconds
                                }
                            }
                            
                            val isLastInGroup = when {
                                // Last message overall
                                nextMessage == null -> true
                                // Different user
                                nextMessage.user.id != message.user.id -> true
                                // More than 5 minutes apart
                                else -> {
                                    val timeDiff = (nextMessage.timestamp ?: nextMessage.date.time) - (message.timestamp ?: message.date.time)
                                    timeDiff > 5 * 60 * 1000 // 5 minutes in milliseconds
                                }
                            }
                            
                            // Add spacing between different users (add extra top padding if first in group and different user)
                            val topPadding = if (isFirstInGroup && prevMessage != null && prevMessage.user.id != message.user.id) {
                                12.dp
                            } else {
                                0.dp
                            }
                            
                            Box(modifier = Modifier.padding(top = topPadding)) {
                                MessageBubble(
                                    message = message,
                                    isUser = isUser,
                                    showAvatar = isFirstInGroup, // Show avatar on first message in group
                                    showUsername = isFirstInGroup, // Only show username on first message
                                    showTimestamp = isLastInGroup // Only show timestamp on last message in group
                                )
                            }
                        }
                    }
                    }
                }
                isLoading && messages.isEmpty() -> {
                    // Show loading indicator only if we have no messages
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No messages yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Start the conversation!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
        
        // Typing indicator
        if (composingUsers.isNotEmpty()) {
            TypingIndicator(users = composingUsers)
        }
        
        // Input
        ChatInput(
            onSendMessage = { text ->
                viewModel.sendMessage(text)
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatRoomHeader(
    room: Room,
    onBack: () -> Unit
) {
    TopAppBar(
        title = { 
            Text(
                room.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                )
            ) 
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
