package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import java.text.SimpleDateFormat
import java.util.*

/**
 * Room list view with search and create chat functionality
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListView(
    onRoomSelected: (Room) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = remember { RoomListViewModel() }
    val rooms by viewModel.rooms.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    // Observe MessageStore to get last messages for rooms
    val messagesByRoom by MessageStore.messages.collectAsState()
    
    var searchText by remember { mutableStateOf(TextFieldValue("")) }
    var showCreateChatDialog by remember { mutableStateOf(false) }
    
    // Track selected room for active state
    val selectedRoom by RoomStore.currentRoom.collectAsState()
    
    // Enrich rooms with last messages from MessageStore
    val enrichedRooms = remember(rooms, messagesByRoom) {
        rooms.map { room ->
            val roomMessages = messagesByRoom[room.jid] ?: emptyList()
            if (roomMessages.isNotEmpty()) {
                // Get the last message (newest) from MessageStore
                val lastMessage = roomMessages.lastOrNull()
                lastMessage?.let { msg ->
                    // Create LastMessage from Message
                    val lastMessageModel = com.ethora.chat.core.models.LastMessage(
                        body = msg.body,
                        date = msg.date,
                        emoji = msg.reaction?.values?.firstOrNull()?.emoji?.firstOrNull(),
                        locationPreview = msg.locationPreview,
                        filename = msg.fileName,
                        mimetype = msg.mimetype,
                        originalName = msg.originalName
                    )
                    // Update room with last message from MessageStore (takes priority over room.lastMessage)
                    room.copy(
                        lastMessage = lastMessageModel,
                        lastMessageTimestamp = msg.timestamp ?: msg.date.time
                    )
                } ?: room
            } else {
                // No messages in MessageStore, use room's lastMessage if available
                room
            }
        }
    }
    
    // Sort rooms by last message timestamp (newest first) - matches web RoomList sorting
    val sortedRooms = remember(enrichedRooms) {
        enrichedRooms.sortedWith(compareByDescending<Room> { room ->
            // Use lastMessageTimestamp if available, otherwise use lastMessage date, then createdAt
            when {
                room.lastMessageTimestamp != null -> room.lastMessageTimestamp!!
                room.lastMessage?.date != null -> room.lastMessage!!.date!!.time
                room.createdAt != null -> {
                    // Parse createdAt string to timestamp
                    try {
                        val createdAt = room.createdAt
                        // Try ISO format first
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(createdAt)?.time
                            ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).parse(createdAt)?.time
                            ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
                else -> 0L // Rooms without messages go to bottom
            }
        }.thenByDescending { room ->
            // Secondary sort by createdAt if timestamps are equal
            room.createdAt?.let { createdAt ->
                try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(createdAt)?.time
                        ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).parse(createdAt)?.time
                        ?: 0L
                } catch (e: Exception) {
                    0L
                }
            } ?: 0L
        })
    }
    
    // Filter rooms based on search text
    val filteredRooms = remember(sortedRooms, searchText.text) {
        if (searchText.text.isBlank()) {
            sortedRooms
        } else {
            val query = searchText.text.lowercase()
            sortedRooms.filter { room ->
                room.title.lowercase().contains(query) ||
                room.name.lowercase().contains(query) ||
                room.description?.lowercase()?.contains(query) == true ||
                room.lastMessage?.body?.lowercase()?.contains(query) == true
            }
        }
    }

    // Toast message
    val toastMessage by viewModel.toastMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Show toast using Snackbar
    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearToast()
        }
    }
    
    // Handle created room - navigate to it
    val createdRoom by viewModel.createdRoom.collectAsState()
    LaunchedEffect(createdRoom) {
        createdRoom?.let { room ->
            onRoomSelected(room)
            viewModel.clearCreatedRoom()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Chats",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    ) 
                },
                actions = {
                    IconButton(onClick = { showCreateChatDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create new chat"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            SearchBar(
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            
            if (isLoading && rooms.isEmpty()) {
                // Only show loader when loading AND no rooms exist (matches web pattern)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredRooms.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (searchText.text.isNotBlank()) "No chats found" else "No chats yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (searchText.text.isNotBlank()) {
                            TextButton(onClick = { searchText = TextFieldValue("") }) {
                                Text("Clear search")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredRooms) { room ->
                        RoomListItem(
                            room = room,
                            isActive = selectedRoom?.jid == room.jid,
                            onClick = { 
                                RoomStore.setCurrentRoom(room)
                                onRoomSelected(room) 
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Create chat dialog
    if (showCreateChatDialog) {
        CreateChatDialog(
            onDismiss = { showCreateChatDialog = false },
            onCreateChat = { title, type, description, picturePath, members ->
                viewModel.createRoom(title, type, description, picturePath, members)
                showCreateChatDialog = false
            }
        )
    }
}

/**
 * Search bar component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    searchText: TextFieldValue,
    onSearchTextChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = searchText,
        onValueChange = onSearchTextChange,
        modifier = modifier.fillMaxWidth(),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        },
        placeholder = { 
            Text(
                "Search chats...",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            ) 
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomListItem(
    room: Room,
    isActive: Boolean,
    onClick: () -> Unit
) {
    // Get room icon or generate initials
    val roomIcon = room.icon
    val roomInitials = getRoomInitials(room.title)
    val backgroundColor = getColorForName(room.title)
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isActive) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            // Room avatar/icon
            RoomAvatar(
                icon = roomIcon,
                initials = roomInitials,
                backgroundColor = backgroundColor,
                size = 64.dp,
                isActive = isActive
            )
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Room name and time row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = room.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isActive) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    room.lastMessage?.let { lastMessage ->
                        lastMessage.date?.let { date ->
                            Text(
                                text = formatTimeToHHMM(date),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Last message/typing indicator row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    // Show typing indicator if someone is typing, otherwise show last message
                    val composingList = room.composingList ?: emptyList()
                    if (room.composing == true && composingList.isNotEmpty()) {
                        Text(
                            text = when {
                                composingList.size == 1 -> "${composingList[0]} is typing"
                                composingList.size == 2 -> "${composingList[0]} and ${composingList[1]} are typing"
                                else -> "${composingList.size} people are typing"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic
                            ),
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        room.lastMessage?.let { lastMessage ->
                            LastMessagePreview(
                                lastMessage = lastMessage,
                                isActive = isActive,
                                modifier = Modifier.weight(1f)
                            )
                        } ?: run {
                            Text(
                                text = "No messages yet",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic
                                ),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    // Pending messages indicator
                    if (room.pendingMessages > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = "${room.pendingMessages} sending",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontStyle = FontStyle.Italic
                                ),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                }
                            )
                        }
                    }
                    
                    // Unread badge
                    if (room.unreadMessages > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    text = if (room.unreadMessages > 99) "99+" else room.unreadMessages.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimary
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Room avatar component - shows icon or initials
 */
@Composable
private fun RoomAvatar(
    icon: String?,
    initials: String,
    backgroundColor: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
    isActive: Boolean
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = if (icon != null && icon.isNotBlank() && icon != "none") {
            androidx.compose.ui.graphics.Color.Transparent
        } else {
            backgroundColor
        },
        shadowElevation = if (isActive) 4.dp else 2.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            if (icon != null && icon.isNotBlank() && icon != "none") {
                AsyncImage(
                    model = icon,
                    contentDescription = "Room icon",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
        }
    }
}

/**
 * Last message preview component - handles different message types
 */
@Composable
private fun LastMessagePreview(
    lastMessage: com.ethora.chat.core.models.LastMessage,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
    
    val emoji = lastMessage.emoji
    val mimetype = lastMessage.mimetype
    
    when {
        // Emoji message
        emoji != null -> {
            Text(
                text = emoji,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier
            )
        }
        // Media messages
        mimetype != null -> {
            val mediaText = when {
                mimetype.startsWith("image/") -> "📷 Photo"
                mimetype.startsWith("video/") -> "🎥 Video"
                mimetype.startsWith("audio/") -> "🎵 Audio"
                else -> "📎 ${lastMessage.originalName ?: lastMessage.filename ?: "File"}"
            }
            Text(
                text = mediaText,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier
            )
        }
        // Text message
        else -> {
            Text(
                text = lastMessage.body,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier
            )
        }
    }
}

/**
 * Get room initials (first 2 letters)
 */
private fun getRoomInitials(name: String): String {
    if (name.isBlank()) return ""
    
    val words = name.trim().split(Regex("\\s+"))
    return when {
        words.size >= 2 -> {
            "${words[0].take(1).uppercase()}${words[1].take(1).uppercase()}"
        }
        words.size == 1 -> {
            val word = words[0]
            if (word.length >= 2) {
                word.take(2).uppercase()
            } else {
                word.take(1).uppercase()
            }
        }
        else -> ""
    }
}

/**
 * Generate color from name (for avatar background)
 */
private fun getColorForName(name: String): androidx.compose.ui.graphics.Color {
    val colors = listOf(
        androidx.compose.ui.graphics.Color(0xFF4287f5), // Blue
        androidx.compose.ui.graphics.Color(0xFF42f5e9), // Cyan
        androidx.compose.ui.graphics.Color(0xFFf542a1), // Pink
        androidx.compose.ui.graphics.Color(0xFFf5a142), // Orange
        androidx.compose.ui.graphics.Color(0xFFa142f5), // Purple
        androidx.compose.ui.graphics.Color(0xFF42f542), // Green
        androidx.compose.ui.graphics.Color(0xFFf54242), // Red
    )
    
    val hash = name.hashCode()
    val index = kotlin.math.abs(hash) % colors.size
    return colors[index]
}

/**
 * Format time to HH:MM format (matches web formatTimeToHHMM)
 */
private fun formatTimeToHHMM(date: java.util.Date): String {
    val now = Calendar.getInstance()
    val messageDate = Calendar.getInstance().apply { time = date }
    
    return when {
        // Same year
        messageDate.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> {
            // Today - show HH:MM
            if (messageDate.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            }
            // This year but not today - show MM/DD
            else {
                SimpleDateFormat("MM/dd", Locale.getDefault()).format(date)
            }
        }
        // Different year - show YYYY/MM/DD
        else -> {
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(date)
        }
    }
}
