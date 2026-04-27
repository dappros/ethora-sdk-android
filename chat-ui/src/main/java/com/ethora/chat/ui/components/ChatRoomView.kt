package com.ethora.chat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.MessageActionsProps
import com.ethora.chat.core.models.MessageProps
import com.ethora.chat.core.models.SendInputProps
import com.ethora.chat.core.store.ChatConnectionStatus
import com.ethora.chat.core.store.ConnectionStore
import com.ethora.chat.core.store.PendingMediaSendQueue
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore
import com.ethora.chat.core.xmpp.XMPPClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun normalizeLocalPart(value: String?): String {
    return value
        ?.trim()
        ?.lowercase()
        ?.substringBefore("/")
        ?.substringBefore("@")
        .orEmpty()
}

private fun isMessageFromCurrentUser(
    message: com.ethora.chat.core.models.Message,
    currentUserXmppUsername: String?,
    currentUserId: String?
): Boolean {
    val currentLocal = normalizeLocalPart(currentUserXmppUsername)
    val messageLocalFromXmpp = normalizeLocalPart(message.user.xmppUsername)
    val messageLocalFromUserJid = normalizeLocalPart(message.user.userJID)
    val messageLocalFromId = normalizeLocalPart(message.user.id)
    val currentIdLocal = normalizeLocalPart(currentUserId)

    if (currentLocal.isNotEmpty()) {
        if (messageLocalFromXmpp == currentLocal) return true
        if (messageLocalFromUserJid == currentLocal) return true
        if (messageLocalFromId == currentLocal) return true
    }
    if (currentIdLocal.isNotEmpty()) {
        if (messageLocalFromId == currentIdLocal) return true
        if (messageLocalFromXmpp == currentIdLocal) return true
        if (messageLocalFromUserJid == currentIdLocal) return true
    }
    return false
}

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentUser by UserStore.currentUser.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val composingUsers by viewModel.composingUsers.collectAsState()
    val scrollRestoreAnchor by viewModel.scrollRestoreAnchor.collectAsState()
    val connectionState by ConnectionStore.state.collectAsState()
    val pendingMediaItems by PendingMediaSendQueue.items.collectAsState()
    
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
    
    // Update lastViewedTimestamp when room is closed (set to current time).
    // Web parity: also write the new read marker to the server's private store
    // (XEP-0049) so other devices see the same read state.
    val disposeScope = rememberCoroutineScope()
    DisposableEffect(room.jid, xmppClient) {
        onDispose {
            val currentTime = System.currentTimeMillis()
            com.ethora.chat.core.store.RoomStore.setLastViewedTimestamp(room.jid, currentTime)
            disposeScope.launch {
                com.ethora.chat.core.store.InitBeforeLoadFlow.writeCurrentTimestamp(
                    xmppClient, room.jid, currentTime
                )
            }
            android.util.Log.d("ChatRoomView", "Room closed: ${room.jid}, lastViewedTimestamp=$currentTime")
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
    var pendingOwnMessageAutoScroll by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                viewModel.processPendingMediaQueue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // True when the viewport ends within ~3 items of the list's tail. Used to
    // decide "new incoming → auto-scroll" AND to know whether the user has
    // manually scrolled up (if not near bottom, they're reading older messages).
    // Web's equivalent: `isUserScrolledUp.current = distanceFromBottom > 150`.
    val isNearBottom by remember {
        derivedStateOf {
            if (messages.isEmpty()) return@derivedStateOf true
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount == 0) return@derivedStateOf true
            val lastVisibleIdx = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val isAtEndOfContent = !listState.canScrollForward
            isAtEndOfContent || lastVisibleIdx >= totalCount - 3
        }
    }

    // (Removed the snapshotFlow-based `userHasScrolledUp` latch — it read
    // listState during the brief window when MAM had populated the list but
    // the initial auto-scroll hadn't run yet, so it flipped to "user scrolled
    // up" by mistake and disabled subsequent auto-scroll. The unified
    // messages.size effect below uses listState.layoutInfo directly AFTER the
    // auto-scroll has settled, which avoids the race.)

    // Two-stage scroll-to-bottom (option Q11=c):
    //   1) INSTANT jump to the last index via `scrollToItem(lastIdx)`. This
    //      guarantees the tail of the list is actually laid out — no more
    //      "landed at a random spot" because `animateScrollToItem` decided to
    //      short-circuit before the item was composed.
    //   2) `animateScrollBy(Float.MAX_VALUE)` (clamped by LazyColumn to max
    //      scroll). Flushes any remaining pixels below the item — trailing
    //      contentPadding, the typing-indicator item's own height, or late
    //      image expansions — and the animation settles smoothly at the real
    //      bottom instead of a few pixels short.
    suspend fun scrollToTrueBottom(animate: Boolean = true) {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) return
        listState.scrollToItem(total - 1)
        if (animate) {
            listState.animateScrollBy(Float.MAX_VALUE)
        } else {
            listState.scrollBy(Float.MAX_VALUE)
        }
    }

    // ---- Show/hide the "scroll to bottom" FAB ----
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, messages.size) {
        val isAtBottom = !listState.canScrollForward
        showScrollToBottom = !isAtBottom && messages.isNotEmpty()
        if (isAtBottom) unreadCount = 0
    }

    // ---- Unified scroll-on-messages-change effect ----
    // Matches web's MessageList.tsx restoreScrollPosition + new-message logic:
    //   • First non-empty load for a room → jump to bottom (no animation).
    //   • Every subsequent increase → if the user is AT or NEAR the bottom
    //     (last ≥ 3 items visible or !canScrollForward), re-pin to the new
    //     bottom; otherwise bump `unreadCount`.
    //
    // The "second load wave" bug is now impossible because the near-bottom
    // check always runs AFTER the previous auto-scroll has settled — if we
    // were pinned, canScrollForward is still false and we re-pin.
    var initialAutoScrollDone by remember(room.jid) { mutableStateOf(false) }
    LaunchedEffect(room.jid, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect

        // Let the new items measure before we inspect listState.
        kotlinx.coroutines.delay(60)

        if (!initialAutoScrollDone) {
            // Wait for LazyColumn to finish composing the new items — without
            // this, animateScrollToItem can land BEFORE the last item is laid
            // out, leaving the list a few pixels above the true bottom.
            kotlinx.coroutines.withTimeoutOrNull(600) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it >= messages.size }
            }
            // Initial jump.
            scrollToTrueBottom(animate = false)
            // Async renderers (AsyncImage, media players, avatar bitmaps) can
            // grow items AFTER first layout, which shifts the bottom further
            // down. Re-pin a few times, but only while the user hasn't scrolled
            // away — so gestures aren't fought.
            repeat(4) {
                kotlinx.coroutines.delay(120)
                val total = listState.layoutInfo.totalItemsCount
                val lastVisibleIdx = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val nearBottom = !listState.canScrollForward ||
                    (total > 0 && lastVisibleIdx >= total - 2)
                if (!nearBottom) return@repeat
                scrollToTrueBottom(animate = false)
            }
            initialAutoScrollDone = true
            lastMessageCount = messages.size
            unreadCount = 0
            return@LaunchedEffect
        }

        if (messages.size > lastMessageCount && !isLoadingMore) {
            val delta = messages.size - lastMessageCount
            val total = listState.layoutInfo.totalItemsCount
            val lastVisibleIdx = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val atEndOfContent = !listState.canScrollForward
            val atOrNearBottom = atEndOfContent ||
                (total > 0 && lastVisibleIdx >= total - 3)
            if (atOrNearBottom) {
                scrollToTrueBottom(animate = true)
                unreadCount = 0
            } else {
                unreadCount += delta
            }
        }
        lastMessageCount = messages.size
    }

    // ---- Scroll after the user sends a message — ALWAYS ----
    LaunchedEffect(messages.size, pendingOwnMessageAutoScroll) {
        if (!pendingOwnMessageAutoScroll) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        scrollToTrueBottom(animate = true)
        unreadCount = 0
        pendingOwnMessageAutoScroll = false
    }

    // Debug logging
    LaunchedEffect(messages.size, isLoading) {
        if (messages.isEmpty() && !isLoading) {
            android.util.Log.w("ChatRoomView", "No messages displayed but not loading!")
        }
    }

    // Keyboard-aware auto-scroll: when the IME opens, pin the list to the bottom
    // so the newest message is visible above the keyboard. Matches web's onFocus scroll.
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isImeVisible = imeBottom > 0
    var wasImeVisible by remember { mutableStateOf(isImeVisible) }
    LaunchedEffect(isImeVisible, messages.size) {
        if (isImeVisible && !wasImeVisible && messages.isNotEmpty()) {
            val isAtBottomNow = !listState.canScrollForward
            // Use totalItemsCount so the trailing typing-indicator item is accounted for.
            val total = listState.layoutInfo.totalItemsCount
            val nearBottom = isAtBottomNow ||
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                    total > 0 && it >= total - 3
                } == true
            if (nearBottom) {
                // Small delay lets the list re-layout after the IME insets apply.
                kotlinx.coroutines.delay(50)
                scrollToTrueBottom(animate = true)
            }
        }
        wasImeVisible = isImeVisible
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
                val showBackButton = if (config?.disableRooms == true) {
                    false
                } else {
                    headerSettings?.backButtonDisabled != true
                }
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
                    showBackButton = showBackButton
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
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

                            // "New messages" delimiter — synthetic Message injected by
                            // MessageStore.normalizeDelimiterPosition. Renders a centered
                            // pill (web parity with MessageContainer.tsx:91-95) and
                            // short-circuits date-separator + grouping + bubble logic.
                            if (message.id == "delimiter-new") {
                                NewMessageDivider(colors = config?.colors)
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
                                
                                val isUser = isMessageFromCurrentUser(
                                    message = message,
                                    currentUserXmppUsername = currentUserXmppUsername,
                                    currentUserId = viewModel.currentUserId
                                )
                                
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
                                    val customMessageComponent = config?.customComponents?.customMessageComponent
                                    if (customMessageComponent != null) {
                                        customMessageComponent(
                                            MessageProps(
                                                message = message,
                                                isUser = isUser,
                                                isReply = message.isReply == true
                                            )
                                        )
                                    } else {
                                        val pendingMediaItem = pendingMediaItems
                                            .firstOrNull { it.messageId == message.id }
                                        val pendingMediaStatus = pendingMediaItem?.status
                                        val sendFailed = isUser && (
                                            pendingMediaStatus == com.ethora.chat.core.store.PendingMediaSendStatus.FAILED_WAITING_RETRY ||
                                                pendingMediaStatus == com.ethora.chat.core.store.PendingMediaSendStatus.PERMANENTLY_FAILED ||
                                                ((message.pending == true || pendingMediaStatus == com.ethora.chat.core.store.PendingMediaSendStatus.QUEUED) &&
                                                    connectionState.status != ChatConnectionStatus.ONLINE)
                                        )
                                        val openFailedActions = { tapX: Float, tapY: Float, left: Float, top: Float, right: Float, bottom: Float ->
                                            contextMenuMessage = message
                                            contextMenuTap = Pair(tapX, tapY)
                                            contextMenuBounds = Pair(left, top) to Pair(right, bottom)
                                        }
                                        MessageBubble(
                                            message = message,
                                            isUser = isUser,
                                            parentMessage = parentMessage,
                                            showAvatar = isFirstInGroup, // Show avatar on first message in group
                                            showUsername = isFirstInGroup, // Only show username on first message
                                            // Web parity: timestamp only on the LAST message of a
                                            // consecutive same-sender group (within the 5-minute
                                            // window). Otherwise every bubble carries "20:25" which
                                            // is visual noise. `isLastInGroup` is already computed
                                            // above from chronological neighbours + time window.
                                            showTimestamp = isLastInGroup,
                                            pendingMediaStatus = pendingMediaStatus,
                                            sendFailed = sendFailed,
                                            onMediaClick = { msg -> previewMessage = msg },
                                            onFailedClick = openFailedActions,
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

                        // Typing indicator as the LAST item inside the scroll
                        // container — matches web's Composing.tsx which renders
                        // inside MessagesScroll. This way it scrolls together
                        // with the messages, never overlaps any bubble, and
                        // "user at bottom" + auto-scroll keeps it visible.
                        if (composingUsers.isNotEmpty()) {
                            item(key = "typing-indicator") {
                                TypingIndicator(users = composingUsers)
                            }
                        }
                    }

                    // (Typing indicator is now a LazyColumn item above; no
                    // overlay or sibling row is needed.)

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
                                        coroutineScope.launch {
                                            scrollToTrueBottom(animate = true)
                                            unreadCount = 0
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
                val isUserMessage = isMessageFromCurrentUser(
                    message = msg,
                    currentUserXmppUsername = viewModel.currentUserXmppUsername,
                    currentUserId = viewModel.currentUserId
                )
                val dismissMenu = {
                    contextMenuMessage = null
                    contextMenuTap = Pair(0f, 0f)
                    contextMenuBounds = Pair(0f, 0f) to Pair(0f, 0f)
                }
                val customMessageActions = config?.customComponents?.customMessageActionsComponent
                if (customMessageActions != null) {
                    customMessageActions(
                        MessageActionsProps(
                            message = msg,
                            isUser = isUserMessage,
                            onCopy = { clipboardManager.setText(AnnotatedString(msg.body)) },
                            onEdit = {
                                editMessageId = msg.id
                                editMessageText = msg.body
                                dismissMenu()
                            },
                            onDelete = {
                                deleteConfirmMessageId = msg.id
                                dismissMenu()
                            },
                            onDismiss = dismissMenu
                        )
                    )
                } else {
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
                        onDismiss = dismissMenu,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(msg.body))
                        },
                        onEdit = {
                            editMessageId = msg.id
                            editMessageText = msg.body
                            dismissMenu()
                        },
                        onDelete = {
                            deleteConfirmMessageId = msg.id
                            dismissMenu()
                        },
                        onResend = {
                            viewModel.resendMessage(msg.id)
                            dismissMenu()
                        }
                    )
                }
            }
        }
        
        // Typing indicator is now rendered as the final `item` inside the
        // LazyColumn (see above), so the list itself carries it. No sibling
        // row here — that previously ate ~46 dp off the messages Box and made
        // the last bubble feel hidden when someone started typing.

        // Input (disable media if config says so)
        val disableMedia = config?.disableMedia == true
        val canSendMessage = true
        val handleSendMessage: (String, String?) -> Unit = sendHandler@{ text, parentId ->
            if (editMessageId != null) {
                // Edit mode: edit existing message
                viewModel.editMessage(editMessageId!!, text)
                editMessageId = null
                editMessageText = ""
            } else {
                // Normal mode: send new message (could be a reply)
                viewModel.sendMessage(text, parentId)
                // ALWAYS pin to the bottom on send — web parity. Arming the
                // flag AND kicking off an immediate scroll covers both the race
                // where the optimistic message hasn't reached the LazyColumn yet
                // (flag causes the LaunchedEffect to fire when it does) and the
                // common case where it's already there.
                pendingOwnMessageAutoScroll = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(60)
                    scrollToTrueBottom(animate = true)
                }
                if (replyingToMessage != null) {
                    replyingToMessage = null
                }
            }
            // Stop typing when message is sent
            viewModel.sendStopTyping()
        }
        val handleSendMedia: ((java.io.File, String) -> Unit)? = if (disableMedia) null else mediaHandler@{ file, mimeType ->
            viewModel.sendMedia(file, mimeType)
            // Same always-scroll-on-send rule for media.
            pendingOwnMessageAutoScroll = true
            coroutineScope.launch {
                kotlinx.coroutines.delay(60)
                scrollToTrueBottom(animate = true)
            }
            // Stop typing when media is sent
            viewModel.sendStopTyping()
        }

        val customInputComponent = config?.customComponents?.customInputComponent
        if (customInputComponent != null) {
            customInputComponent(
                SendInputProps(
                    onSendMessage = { text -> handleSendMessage(text, replyingToMessage?.id) },
                    onSendMedia = if (handleSendMedia == null) null else mediaBytes@{ bytes, mime ->
                        viewModel.sendMedia(bytes, mime)
                    },
                    placeholderText = "Type a message...",
                    messageText = if (editMessageId != null) editMessageText else "",
                    isEditing = editMessageId != null,
                    editMessageId = editMessageId,
                    canSend = canSendMessage
                )
            )
        } else {
            ChatInput(
                onSendMessage = handleSendMessage,
                onSendMedia = handleSendMedia,
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
                },
                canSendMessage = canSendMessage
            )
        }
        
        // File preview dialog
        val roomImageUrls = remember(messages) {
            messages
                .filter { it.mimetype?.startsWith("image/") == true }
                .mapNotNull { msg -> msg.location?.takeIf { it.isNotBlank() } ?: msg.locationPreview?.takeIf { it.isNotBlank() } }
        }
        val initialImageIndex = remember(previewMessage, roomImageUrls) {
            val previewUrl = previewMessage?.let { it.location?.takeIf { u -> u.isNotBlank() } ?: it.locationPreview?.takeIf { u -> u.isNotBlank() } }
            if (previewUrl == null) 0 else roomImageUrls.indexOf(previewUrl).takeIf { it >= 0 } ?: 0
        }
        FilePreviewDialog(
            message = previewMessage,
            galleryImageUrls = roomImageUrls,
            galleryInitialIndex = initialImageIndex,
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
