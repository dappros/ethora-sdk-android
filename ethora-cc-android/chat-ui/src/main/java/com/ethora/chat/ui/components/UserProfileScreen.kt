package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.User
import com.ethora.chat.core.models.createRoomFromApi
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.launch

/**
 * User profile screen - shows user info and allows creating 1v1 chat
 * Matches web: UserProfileModal
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    user: User,
    onBack: () -> Unit,
    onCreateChat: (Room) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // User avatar
            UserAvatar(
                user = user,
                modifier = Modifier.size(120.dp)
            )
            
            // User name
            Text(
                text = user.fullName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // About section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = user.description ?: "No description",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            
            // Action buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Message button - create 1v1 chat
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            errorMessage = null
                            try {
                                // Create private room via API
                                val apiRoom = RoomsAPIHelper.createPrivateRoom(
                                    username = user.xmppUsername ?: user.id
                                )
                                
                                // Convert to Room and add to store
                                val newRoom = createRoomFromApi(
                                    apiRoom = apiRoom,
                                    conferenceDomain = AppConfig.defaultConferenceDomain,
                                    usersArrayLength = 2
                                )
                                
                                // Add to existing rooms
                                val currentRooms = RoomStore.rooms.value.toMutableList()
                                if (!currentRooms.any { it.id == newRoom.id || it.jid == newRoom.jid }) {
                                    currentRooms.add(0, newRoom)
                                    RoomStore.setRooms(currentRooms)
                                }
                                
                                // Set as current room
                                RoomStore.setCurrentRoom(newRoom)
                                
                                // Navigate to chat
                                onCreateChat(newRoom)
                                
                                // Clear selected user
                                UserStore.clearSelectedUser()
                            } catch (e: Exception) {
                                errorMessage = "Failed to create chat: ${e.message}"
                                android.util.Log.e("UserProfileScreen", "Failed to create private room", e)
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isLoading) "Creating..." else "Message")
                }
                
                // Copy User ID button
                OutlinedButton(
                    onClick = {
                        val userId = user.xmppUsername ?: user.id
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(userId))
                        // Show snackbar would be nice but keeping it simple for now
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy User ID")
                }
            }
            
            // Error message
            errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
