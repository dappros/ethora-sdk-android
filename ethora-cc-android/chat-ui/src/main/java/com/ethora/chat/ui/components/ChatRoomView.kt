package com.ethora.chat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
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
    val viewModel = remember(room.jid, xmppClient) { ChatRoomViewModel(room, xmppClient) }
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsState()
    val composingUsers by viewModel.composingUsers.collectAsState()
    
    // Update lastViewedTimestamp when room is opened (set to 0 to mark all as read)
    LaunchedEffect(room.jid) {
        com.ethora.chat.core.store.RoomStore.setLastViewedTimestamp(room.jid, 0)
        android.util.Log.d("ChatRoomView", "Room opened: $room.jid, set lastViewedTimestamp to 0")
    }
    
    // Update lastViewedTimestamp when room is closed (set to current time)
    DisposableEffect(room.jid) {
        onDispose {
            val currentTime = System.currentTimeMillis()
            com.ethora.chat.core.store.RoomStore.setLastViewedTimestamp(room.jid, currentTime)
            android.util.Log.d("ChatRoomView", "Room closed: $room.jid, set lastViewedTimestamp to $currentTime")
        }
    }
    
    // Coroutine scope for scroll animations
    val coroutineScope = rememberCoroutineScope()
    
    // Clipboard manager
    val clipboardManager = LocalClipboardManager.current
    
    // State for file preview dialog
    var previewMessage by remember { mutableStateOf<com.ethora.chat.core.models.Message?>(null) }
    
    // State for context menu
    var contextMenuMessage by remember { mutableStateOf<com.ethora.chat.core.models.Message?>(null) }
    var contextMenuPosition by remember { mutableStateOf(Pair(0f, 0f)) }
    
    // State for edit mode
    var editMessageId by remember { mutableStateOf<String?>(null) }
    var editMessageText by remember { mutableStateOf("") }
    
    // State for chat info screen
    var showChatInfo by remember { mutableStateOf(false) }
    
    // State for replying
    var replyingToMessage by remember { mutableStateOf<com.ethora.chat.core.models.Message?>(null) }
    
    // Selected user for profile view
    val selectedUser by UserStore.selectedUser.collectAsState()
    
    // Get saved scroll position or default to 0 (bottom in reverse layout)
    val savedScrollPosition = remember(room.jid) { 
        ScrollPositionStore.getScrollPosition(room.jid) 
    }
    
    // LazyListState for scroll detection - restore saved position or start at bottom
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition ?: 0
    )
    
    // Track if scroll to bottom button should be shown
    // In reverseLayout, we're at bottom when firstVisibleItemIndex is 0 and scrollOffset is small
    var showScrollToBottom by remember { mutableStateOf(false) }
    
    // Track unread count (messages that arrived while scrolled up)
    var unreadCount by remember { mutableStateOf(0) }
    var lastMessageCount by remember { mutableStateOf(messages.size) }
    
    // Check scroll position to show/hide scroll to bottom button
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        // In reverseLayout: index 0 = oldest messages (top), higher indices = newer messages (bottom)
        // We're at bottom when firstVisibleItemIndex is 0 and scrollOffset is small (< 100px)
        val isAtBottom = listState.firstVisibleItemIndex == 0 && 
                         listState.firstVisibleItemScrollOffset < 100
        
        // Show button if we're not at bottom and have messages
        val shouldShow = !isAtBottom && messages.isNotEmpty()
        
        // If user scrolled to bottom, reset unread count
        if (isAtBottom) {
            unreadCount = 0
        }
        
        showScrollToBottom = shouldShow
    }
    
    // Track new messages when scrolled up
    LaunchedEffect(messages.size) {
        if (messages.size > lastMessageCount && !showScrollToBottom) {
            // New messages arrived but we're scrolled up
            // Don't increment if we're already at bottom (will be handled by scroll effect)
            val isAtBottom = listState.firstVisibleItemIndex == 0 && 
                             listState.firstVisibleItemScrollOffset < 100
            if (!isAtBottom) {
                unreadCount += (messages.size - lastMessageCount)
            }
        }
        lastMessageCount = messages.size
    }
    
    // Debug logging
    LaunchedEffect(messages.size, isLoading) {
        android.util.Log.d("ChatRoomView", "Messages in UI: ${messages.size}, isLoading: $isLoading")
        if (messages.isEmpty() && !isLoading) {
            android.util.Log.w("ChatRoomView", "No messages displayed but not loading!")
        }
    }
    
    // Log message details for debugging
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            android.util.Log.d("ChatRoomView", "First message: ${messages.first().body.take(50)}, Last: ${messages.last().body.take(50)}")
        }
    }
    
    // Auto-scroll to bottom when entering chat (if no saved position)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (savedScrollPosition == null) {
                // No saved position, scroll to bottom (index 0 in reverse layout = newest messages at bottom)
                kotlinx.coroutines.delay(100) // Small delay to ensure layout is ready
                listState.animateScrollToItem(0)
                android.util.Log.d("ChatRoomView", "Auto-scrolled to bottom (newest messages)")
            } else {
                // Restore saved position
                kotlinx.coroutines.delay(100)
                listState.scrollToItem(savedScrollPosition)
                android.util.Log.d("ChatRoomView", "Restored scroll position: $savedScrollPosition")
            }
        }
    }
    
    // Save scroll position when navigating back
    DisposableEffect(room.jid) {
        onDispose {
            val currentIndex = listState.firstVisibleItemIndex
            ScrollPositionStore.saveScrollPosition(room.jid, currentIndex)
            android.util.Log.d("ChatRoomView", "Saved scroll position on dispose: $currentIndex")
        }
    }
    
    // Scroll position restoration
    // Save scroll position before loading older messages, restore after loading
    
    // Detect when user scrolls near the visual top (older messages) using pixel-based detection
    // User wants: when scroll position reaches 1050px (150px from top of 1200px area), trigger loading
    var lastLoadTrigger by remember { mutableStateOf(0L) }
    
    // Use snapshotFlow to observe scroll changes more reliably - don't restart on state changes
    // In reverseLayout: index 0 = newest (bottom), higher indices = older (top)
    // Trigger when scrolling up and reaching near the top (oldest messages)
    // Scroll listener for loading older messages
    LaunchedEffect(listState) {
        snapshotFlow { 
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val visibleItems = layoutInfo.visibleItemsInfo
            
            if (totalItems == 0 || visibleItems.isEmpty()) {
                Triple(0, 0, 0)
            } else {
                val topVisibleItemIndex = visibleItems.lastOrNull()?.index ?: 0
                Triple(topVisibleItemIndex, totalItems, listState.firstVisibleItemScrollOffset)
            }
        }
        .collect { (topVisibleItemIndex, totalItems, _) ->
            if (totalItems == 0) return@collect
            
            val currentIsLoadingMore = isLoadingMore
            val currentIsLoading = isLoading
            val currentHasMore = hasMoreMessages
            
            // Trigger when top visible item is within 5 items of total history
            val itemThreshold = 5
            val topItemThreshold = (totalItems - itemThreshold).coerceAtLeast(0)
            val nearTop = topVisibleItemIndex >= topItemThreshold

            if (nearTop) {
                android.util.Log.d(
                    "ChatRoomView",
                    "📜 Scroll logic: topVisibleItemIndex=$topVisibleItemIndex, total=$totalItems"
                )
            }

            val now = System.currentTimeMillis()
            val shouldTrigger = nearTop && 
                               !currentIsLoadingMore && 
                               !currentIsLoading && 
                               currentHasMore &&
                               (now - lastLoadTrigger) > 500

            if (shouldTrigger) {
                lastLoadTrigger = now
                android.util.Log.i("ChatRoomView", "🚀 Triggering loadMoreMessages() at totalItems=$totalItems")
                viewModel.loadMoreMessages()
            }
        }
    }
    

    // Get chat config
    val config by com.ethora.chat.core.store.ChatStore.config.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Show user profile screen if user is selected
        selectedUser?.let { user ->
            UserProfileScreen(
                user = user,
                onBack = {
                    UserStore.clearSelectedUser()
                },
                onCreateChat = { newRoom: Room ->
                    UserStore.clearSelectedUser()
                    onBack() // Go back to room list
                    // The room will be selected automatically via RoomStore.setCurrentRoom
                }
            )
            return@Column
        }
        
        // Show chat info screen if requested, otherwise show chat room
        if (showChatInfo) {
            ChatInfoScreen(
                room = room,
                onBack = { showChatInfo = false }
            )
        } else {
            // Header (only if not disabled)
            if (config?.disableHeader != true) {
                ChatRoomHeader(
                    room = room,
                    onBack = {
                        // Save scroll position before navigating back
                        val currentIndex = listState.firstVisibleItemIndex
                        ScrollPositionStore.saveScrollPosition(room.jid, currentIndex)
                        android.util.Log.d("ChatRoomView", "Saved scroll position on back: $currentIndex")
                        onBack()
                    },
                    onInfoClick = { showChatInfo = true }
                )
            }
        
        // Messages list
        Box(modifier = Modifier.weight(1f)) {
            when {
                messages.isNotEmpty() -> {
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
                                messages.getOrNull(index)?.id ?: "item_$index"
                            }
                        ) { index ->
                            val message = messages.getOrNull(index)
                            
                            if (message == null) {
                                android.util.Log.e("ChatRoomView", "Message is null at index $index")
                                return@items
                            }
                            
                            // Calculate if we should show date separator
                            // In newest-first list, messages[index+1] is OLDER than messages[index]
                            // We show separator if day changes between current and NEXT (older) item
                            val nextMessage = if (index < messages.size - 1) messages[index + 1] else null
                            val showDateSeparator = nextMessage?.let { next ->
                                val currentDate = java.util.Calendar.getInstance().apply {
                                    time = message.date
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                val nextDate = java.util.Calendar.getInstance().apply {
                                    time = next.date
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                // Show separator if dates are different
                                currentDate.get(java.util.Calendar.YEAR) != nextDate.get(java.util.Calendar.YEAR) ||
                                currentDate.get(java.util.Calendar.DAY_OF_YEAR) != nextDate.get(java.util.Calendar.DAY_OF_YEAR)
                            } ?: true // Show separator for the very last (oldest) message
                            
                            // Show date separator if needed, then the message
                            Column {
                                if (showDateSeparator) {
                                    DateSeparator(date = message.date)
                                }
                                
                                // Check if this message is from the current user
                                // Compare by checking if senderJID/xmppUsername contains the current user's xmppUsername
                                val currentUserXmppUsername = viewModel.currentUserXmppUsername
                                val messageXmppUsername = message.user.xmppUsername ?: ""
                                val messageUserJID = message.user.userJID ?: ""
                                
                                val isUser = when {
                                    // First check: if current user's xmppUsername is not null, check if message's xmppUsername or userJID contains it
                                    !currentUserXmppUsername.isNullOrBlank() -> {
                                        val containsXmppUsername = messageXmppUsername.isNotBlank() &&
                                                messageXmppUsername.contains(currentUserXmppUsername, ignoreCase = false)
                                        val containsUserJID = messageUserJID.isNotBlank() &&
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
                                
                                android.util.Log.d("ChatRoomView", "  Message from: xmppUsername=$messageXmppUsername, userJID=$messageUserJID, isUser=$isUser (current: xmppUsername=$currentUserXmppUsername)")
                                
                                // Determine if this is the first message in a group
                                // Group messages from the same user that are within 5 minutes of each other
                                // Chronological previous is index + 1 (older)
                                // Chronological next is index - 1 (newer)
                                val prevChronological = if (index < messages.size - 1) messages[index + 1] else null
                                val nextChronological = if (index > 0) messages[index - 1] else null
                                
                                val isFirstInGroup = when {
                                    // Chronologically first (no older message from same user within window)
                                    prevChronological == null -> true
                                    // Different user
                                    prevChronological.user.id != message.user.id -> true
                                    // More than 5 minutes apart (prevChronological is older)
                                    else -> {
                                        val timeDiff = (message.timestamp ?: message.date.time) - (prevChronological.timestamp ?: prevChronological.date.time)
                                        timeDiff > 5 * 60 * 1000 // 5 minutes in milliseconds
                                    }
                                }
                                
                                val isLastInGroup = when {
                                    // Chronologically last (no newer message from same user within window)
                                    nextChronological == null -> true
                                    // Different user
                                    nextChronological.user.id != message.user.id -> true
                                    // More than 5 minutes apart (nextChronological is newer)
                                    else -> {
                                        val timeDiff = (nextChronological.timestamp ?: nextChronological.date.time) - (message.timestamp ?: message.date.time)
                                        timeDiff > 5 * 60 * 1000 // 5 minutes in milliseconds
                                    }
                                }
                                
                                // Find parent message for replies
                                val parentMessage = if (message.isReply == true && message.mainMessage != null) {
                                    messages.find { it.id == message.mainMessage }
                                } else null
                                
                                // Add spacing between different users (add extra top padding if first in group and different user)
                                val topPadding = if (isFirstInGroup && prevChronological != null && prevChronological.user.id != message.user.id) {
                                    12.dp
                                } else {
                                    0.dp
                                }
                                
                                Box(modifier = Modifier.padding(top = topPadding)) {
                                    MessageBubble(
                                        message = message,
                                        isUser = isUser,
                                        parentMessage = parentMessage,
                                        showAvatar = isFirstInGroup, // Show avatar on first message in group
                                        showUsername = isFirstInGroup, // Only show username on first message
                                        showTimestamp = true,
                                        onMediaClick = { msg -> previewMessage = msg },
                                        onLongPress = { x, y ->
                                            contextMenuMessage = message
                                            contextMenuPosition = Pair(x, y)
                                        },
                                        onAvatarClick = { user ->
                                            // Don't show profile for deleted users
                                            if (user.name != "Deleted User") {
                                                UserStore.setSelectedUser(user)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    // History loader overlayed at the top of the messages list when loading more
                    // Positioned over the oldest messages (top of the list in reverseLayout)
                    if (isLoadingMore) {
                        Box(
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Loading older messages...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    
                    // Scroll to bottom button (floating action button) with animation
                    Box(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showScrollToBottom,
                            enter = fadeIn(animationSpec = tween(300)) + 
                                    slideInVertically(
                                        initialOffsetY = { it },
                                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                                    ),
                            exit = fadeOut(animationSpec = tween(200)) + 
                                   slideOutVertically(
                                       targetOffsetY = { it },
                                       animationSpec = tween(200)
                                   )
                        ) {
                            Box {
                                FloatingActionButton(
                                    onClick = {
                                        // Scroll to bottom (index 0 in reverseLayout = newest messages)
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(0)
                                            unreadCount = 0 // Reset unread count when scrolling to bottom
                                        }
                                    },
                                    modifier = Modifier.size(48.dp),
                                    shape = CircleShape,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    elevation = FloatingActionButtonDefaults.elevation(
                                        defaultElevation = 6.dp,
                                        pressedElevation = 8.dp
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Scroll to bottom",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                
                                // Unread count badge
                                if (unreadCount > 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .align(androidx.compose.ui.Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-4).dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = androidx.compose.ui.Alignment.Center
                                        ) {
                                            Text(
                                                text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onError,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
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
        
        // Input (disable media if config says so)
        val disableMedia = config?.disableMedia == true
        
        ChatInput(
            onSendMessage = { text, parentId ->
                if (editMessageId != null) {
                    // Edit mode: edit existing message
                    viewModel.editMessage(editMessageId!!, text)
                    editMessageId = null
                    editMessageText = ""
                } else {
                    // Normal mode: send new message (could be a reply)
                    viewModel.sendMessage(text, parentId)
                    if (replyingToMessage != null) {
                        replyingToMessage = null
                    }
                    
                    // Auto-scroll to bottom on user's own message
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                }
                // Stop typing when message is sent
                viewModel.sendStopTyping()
            },
            onSendMedia = if (disableMedia) null else { file, mimeType ->
                viewModel.sendMedia(file, mimeType)
                // Stop typing when media is sent
                viewModel.sendStopTyping()
            },
            onStartTyping = {
                viewModel.sendStartTyping()
            },
            onStopTyping = {
                viewModel.sendStopTyping()
            },
            editText = if (editMessageId != null) editMessageText else null,
            onEditCancel = {
                editMessageId = null
                editMessageText = ""
            },
            replyingToMessage = replyingToMessage,
            onReplyCancel = {
                replyingToMessage = null
            }
        )
        
        // File preview dialog
        FilePreviewDialog(
            message = previewMessage,
            onDismiss = { previewMessage = null }
        )
        
        // Context menu
        contextMenuMessage?.let { msg ->
            val isUserMessage = msg.user.xmppUsername?.let { 
                viewModel.currentUserXmppUsername?.contains(it) == true 
            } ?: false
            
            val isReply = msg.isReply == true
            
            MessageContextMenu(
                message = msg,
                isUser = isUserMessage,
                isReply = isReply,
                visible = true,
                x = contextMenuPosition.first,
                y = contextMenuPosition.second,
                onDismiss = { 
                    contextMenuMessage = null
                    contextMenuPosition = Pair(0f, 0f)
                },
                onReply = {
                    replyingToMessage = msg
                    android.util.Log.d("ChatRoomView", "Reply to message: ${msg.id}")
                },
                onCopy = {
                    // Copy message text to clipboard
                    clipboardManager.setText(AnnotatedString(msg.body))
                },
                onEdit = {
                    // Implement edit - set edit action and show edit input
                    val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
                    if (currentUser != null && msg.user.id == currentUser.id) {
                        // Set edit mode in input
                        editMessageId = msg.id
                        editMessageText = msg.body
                        android.util.Log.d("ChatRoomView", "Edit message: ${msg.id}")
                    }
                },
                onDelete = {
                    // Implement delete
                    val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
                    if (currentUser != null && msg.user.id == currentUser.id) {
                        viewModel.deleteMessage(msg.id)
                        android.util.Log.d("ChatRoomView", "Delete message: ${msg.id}")
                    }
                },
                onReaction = { emoji ->
                    // Implement reaction
                    viewModel.sendReaction(msg.id, emoji)
                    android.util.Log.d("ChatRoomView", "Add reaction $emoji to message: ${msg.id}")
                }
            )
        }
        } // End of else block - closes the else that starts at line 148
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatRoomHeader(
    room: Room,
    onBack: () -> Unit,
    onInfoClick: () -> Unit
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
        actions = {
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Chat info"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
