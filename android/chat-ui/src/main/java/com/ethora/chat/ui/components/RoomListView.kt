package com.ethora.chat.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.store.MessageStore

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
    
    // Filter rooms based on search text
    val filteredRooms = remember(enrichedRooms, searchText.text) {
        if (searchText.text.isBlank()) {
            enrichedRooms
        } else {
            val query = searchText.text.lowercase()
            enrichedRooms.filter { room ->
                room.title.lowercase().contains(query) ||
                room.name.lowercase().contains(query) ||
                room.description?.lowercase()?.contains(query) == true ||
                room.lastMessage?.body?.lowercase()?.contains(query) == true
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Chats") },
            actions = {
                IconButton(onClick = { showCreateChatDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create new chat"
                    )
                }
            }
        )
        
        // Search bar
        SearchBar(
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
        
        if (isLoading) {
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
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(filteredRooms) { room ->
                    RoomListItem(
                        room = room,
                        onClick = { onRoomSelected(room) }
                    )
                }
            }
        }
    }
    
    // Create chat dialog
    if (showCreateChatDialog) {
        CreateChatDialog(
            onDismiss = { showCreateChatDialog = false },
            onCreateChat = { title, type, description, members ->
                viewModel.createRoom(title, type, description, members)
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
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            // Room icon/avatar placeholder
            Surface(
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = room.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = room.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    
                    room.lastMessage?.let { lastMessage ->
                        lastMessage.date?.let { date ->
                            Text(
                                text = formatTime(date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    room.lastMessage?.let { lastMessage ->
                        Text(
                            text = lastMessage.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    } ?: run {
                        Text(
                            text = "No messages yet",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                    
                    if (room.unreadMessages > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
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
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(date: java.util.Date): String {
    val now = System.currentTimeMillis()
    val messageTime = date.time
    val diff = now - messageTime
    
    return when {
        diff < 60 * 1000 -> "now"
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h"
        diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)}d"
        else -> {
            val formatter = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            formatter.format(date)
        }
    }
}
