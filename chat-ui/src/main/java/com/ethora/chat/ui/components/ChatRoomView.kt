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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.activity.compose.BackHandler
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
    val currentUser by UserStore.currentUser.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val composingUsers by viewModel.composingUsers.collectAsState()
    val scrollRestoreAnchor by viewModel.scrollRestoreAnchor.collectAsState()
    
    // Update lastViewedTimestamp when room is opened; send presence to receive real-time messages
    LaunchedEffect(room.jid, xmppClient) {
        com.ethora.chat.core.store.RoomStore.setLastViewedTimestamp(room.jid, 0)
        xmppClient?.let { client ->
            if (client.isFullyConnected()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.sendPresenceInRoom(room.jid)
                }
                android.util.Log.d("ChatRoomView", "Room opened: $room.jid, sent presence")
            }
        }
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
    
    // State for context menu (tap position + message bounds in root coords for placement)
    var contextMenuMessage by remember { mutableStateOf<com.ethora.chat.core.models.Message?>(null) }
    var contextMenuTap by remember { mutableStateOf(Pair(0f, 0f)) }
    var contextMenuBounds by remember { mutableStateOf(Pair(0f, 0f) to Pair(0f, 0f)) }
    // Messages area box position/size in root (for overlay positioning in same coordinate system)
    var messagesBoxOrigin by remember { mutableStateOf(Pair(0f, 0f)) }
    var messagesBoxSize by remember { mutableStateOf(Pair(0f, 0f)) }
    
    // State for edit mode
    var editMessageId by remember { mutableStateOf<String?>(null) }
    var editMessageText by remember { mutableStateOf("") }
    
    // State for chat info screen
    var showChatInfo by remember { mutableStateOf(false) }
    
    // State for replying
    var replyingToMessage by remember { mutableStateOf<com.ethora.chat.core.models.Message?>(null) }

    // State for delete confirmation (like web: modal before delete)
    var deleteConfirmMessageId by remember { mutableStateOf<String?>(null) }

    // Dismiss context menu on back press
    BackHandler(enabled = contextMenuMessage != null) {
        contextMenuMessage = null
        contextMenuTap = Pair(0f, 0f)
        contextMenuBounds = Pair(0f, 0f) to Pair(0f, 0f)
    }
    
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
    var showScrollToBottom by remember { mutableStateOf(false) }
    
    // Track unread count (messages that arrived while scrolled up)
    var unreadCount by remember { mutableStateOf(0) }
    var lastMessageCount by remember { mutableStateOf(messages.size) }
    
    // Check scroll position to show/hide scroll to bottom button
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, messages.size) {
        val isAtBottom = !listState.canScrollForward
        
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
        if (messages.isEmpty() && !isLoading) {
            android.util.Log.w("ChatRoomView", "No messages displayed but not loading!")
        }
    }
    
    // Auto-scroll to bottom when entering chat (only once per room)
    var initialAutoScrollDone by remember(room.jid) { mutableStateOf(false) }
    LaunchedEffect(messages.size, room.jid) {
        if (!initialAutoScrollDone && messages.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
            initialAutoScrollDone = true
            android.util.Log.d("ChatRoomView", "Auto-scrolled to bottom (initial load)")
        }
    }

    // Restore scroll position after loading older messages (avoid jumps)
    LaunchedEffect(scrollRestoreAnchor, isLoadingMore, messages.size) {
        val anchorId = scrollRestoreAnchor
        if (anchorId != null && !isLoadingMore) {
            val anchorIndex = messages.indexOfFirst { it.id == anchorId }
            if (anchorIndex >= 0) {
                kotlinx.coroutines.delay(50)
                listState.scrollToItem(anchorIndex)
                android.util.Log.d("ChatRoomView", "Restored scroll to anchor $anchorId at index $anchorIndex")
            }
            viewModel.clearScrollRestoreAnchor()
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
    
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 300
        }
    }

    // Scroll listener for loading older messages when scrolling to top (like RN/Telegram)
    // RN: onEndReached when scrolled to top (inverted list). We use firstVisibleIndex <= 2 for easier trigger.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
        .collect { (firstVisibleIndex, firstVisibleOffset) ->
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) return@collect
            
            val currentIsLoadingMore = isLoadingMore
            val nearTop = firstVisibleIndex <= 2 && firstVisibleOffset < 300

            val now = System.currentTimeMillis()
            val shouldTrigger = nearTop && 
                               !currentIsLoadingMore && 
                               (now - lastLoadTrigger) > 600

            if (shouldTrigger) {
                lastLoadTrigger = now
                
                // Need ID of OLDEST message (to load messages before it)
                // messages are sorted ascending: messages.first()=oldest
                val oldestMessageId = messages
                    .filter { it.id != "delimiter-new" }
                    .firstOrNull()
                    ?.id
                
                android.util.Log.i("ChatRoomView", "🚀 Load more triggered: firstVisibleIndex=$firstVisibleIndex (nearTop=$nearTop), oldestMessageId=$oldestMessageId")
                viewModel.loadMoreMessages(idOfMessageBefore = oldestMessageId)
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
                val headerSettings = config?.chatHeaderSettings
                val customTitle = headerSettings?.roomTitleOverrides?.let { overrides ->
                    overrides[room.jid]
                        ?: overrides[room.jid.substringBefore("@")]
                }
                val hideChatInfoButton = headerSettings?.chatInfoButtonDisabled == true
                val hideBackButton = headerSettings?.backButtonDisabled == true
                ChatRoomHeader(
                    title = customTitle ?: room.title,
                    onBack = {
                        // Save scroll position before navigating back
                        val currentIndex = listState.firstVisibleItemIndex
                        ScrollPositionStore.saveScrollPosition(room.jid, currentIndex)
                        android.util.Log.d("ChatRoomView", "Saved scroll position on back: $currentIndex")
                        onBack()
                    },
                    onInfoClick = { showChatInfo = true },
                    showInfoButton = !hideChatInfoButton,
                    showBackButton = !hideBackButton
                )
            }
        
        // Messages list
        Box(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { coords ->
                    val pos = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                    messagesBoxOrigin = Pair(pos.x, pos.y)
                    messagesBoxSize = Pair(coords.size.width.toFloat(), coords.size.height.toFloat())
                }
        ) {
            when {
                // Loader only when loading AND no messages (persistence first: show cached messages immediately)
                isLoading && messages.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 12.dp))
                        Text(
                            text = "Loading messages...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                messages.isNotEmpty() -> {
                    // Show messages
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        reverseLayout = false
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
                            // In oldest-first list, messages[index-1] is OLDER than messages[index]
                            // We show separator if day changes between current and PREVIOUS (older) item
                            val prevMessage = if (index > 0) messages[index - 1] else null
                            val showDateSeparator = prevMessage?.let { prev ->
                                val currentDate = java.util.Calendar.getInstance().apply {
                                    time = message.date
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                val prevDate = java.util.Calendar.getInstance().apply {
                                    time = prev.date
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                // Show separator if dates are different
                                currentDate.get(java.util.Calendar.YEAR) != prevDate.get(java.util.Calendar.YEAR) ||
                                currentDate.get(java.util.Calendar.DAY_OF_YEAR) != prevDate.get(java.util.Calendar.DAY_OF_YEAR)
                            } ?: true // Show separator for the very first (oldest) message
                            
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
                                
                                // Determine if this is the first message in a group
                                // Group messages from the same user that are within 5 minutes of each other
                                // Chronological previous is index - 1 (older)
                                // Chronological next is index + 1 (newer)
                                val prevChronological = if (index > 0) messages[index - 1] else null
                                val nextChronological = if (index < messages.size - 1) messages[index + 1] else null
                                
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
                                        onLongPress = { tapX, tapY, left, top, right, bottom ->
                                            contextMenuMessage = message
                                            contextMenuTap = Pair(tapX, tapY)
                                            contextMenuBounds = Pair(left, top) to Pair(right, bottom)
                                        },
                                        onAvatarClick = { user ->
                                            val disableProfiles = config?.disableProfilesInteractions == true
                                            if (!disableProfiles && user.name != "Deleted User") {
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
                    if (isLoadingMore && isAtTop) {
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
                                            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
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
            // Context menu overlay inside same Box so coordinates match
            contextMenuMessage?.let { msg ->
                val (boxLeft, boxTop) = messagesBoxOrigin
                val (boxW, boxH) = messagesBoxSize
                val relLeft = contextMenuBounds.first.first - boxLeft
                val relTop = contextMenuBounds.first.second - boxTop
                val relRight = contextMenuBounds.second.first - boxLeft
                val relBottom = contextMenuBounds.second.second - boxTop
                val currentUserXmppUsername = viewModel.currentUserXmppUsername
                val messageXmppUsername = msg.user.xmppUsername ?: ""
                val messageUserJID = msg.user.userJID ?: ""
                val isUserMessage = when {
                    !currentUserXmppUsername.isNullOrBlank() -> {
                        val containsXmppUsername = messageXmppUsername.isNotBlank() &&
                            messageXmppUsername.contains(currentUserXmppUsername, ignoreCase = false)
                        val containsUserJID = messageUserJID.isNotBlank() &&
                            messageUserJID.contains(currentUserXmppUsername, ignoreCase = false)
                        containsXmppUsername || containsUserJID
                    }
                    else -> {
                        val currentUserId = viewModel.currentUserId
                        val messageUserId = msg.user.id
                        !currentUserId.isNullOrBlank() && !messageUserId.isNullOrBlank() &&
                            currentUserId == messageUserId
                    }
                }
                MessageContextMenu(
                    message = msg,
                    isUser = isUserMessage,
                    visible = true,
                    tapX = contextMenuTap.first,
                    tapY = contextMenuTap.second,
                    boundsLeft = relLeft,
                    boundsTop = relTop,
                    boundsRight = relRight,
                    boundsBottom = relBottom,
                    containerWidthPx = boxW,
                    containerHeightPx = boxH,
                    onDismiss = {
                        contextMenuMessage = null
                        contextMenuTap = Pair(0f, 0f)
                        contextMenuBounds = Pair(0f, 0f) to Pair(0f, 0f)
                    },
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(msg.body))
                    },
                    onEdit = {
                        editMessageId = msg.id
                        editMessageText = msg.body
                        contextMenuMessage = null
                        contextMenuTap = Pair(0f, 0f)
                        contextMenuBounds = Pair(0f, 0f) to Pair(0f, 0f)
                    },
                    onDelete = {
                        deleteConfirmMessageId = msg.id
                        contextMenuMessage = null
                        contextMenuTap = Pair(0f, 0f)
                        contextMenuBounds = Pair(0f, 0f) to Pair(0f, 0f)
                    }
                )
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
                        listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
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
        
        // Delete confirmation dialog (like web ChatWrapper delete modal)
        if (deleteConfirmMessageId != null) {
            AlertDialog(
                onDismissRequest = { deleteConfirmMessageId = null },
                title = { Text("Delete message") },
                text = { Text("Are you sure you want to delete this message?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteMessage(deleteConfirmMessageId!!)
                            deleteConfirmMessageId = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmMessageId = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
        } // End of else block - closes the else that starts at line 148
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatRoomHeader(
    title: String,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
    showInfoButton: Boolean,
    showBackButton: Boolean
) {
    Surface(
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1
            )

            if (showInfoButton) {
                IconButton(onClick = onInfoClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Chat info"
                    )
                }
            }
        }
    }
}
