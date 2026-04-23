package com.ethora.chat.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.ethora.chat.core.models.RoomMember
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.UserStore
import java.io.File

/**
 * Chat type option (matches web ChatAccessOption)
 */
data class ChatTypeOption(
    val name: String,
    val id: String
)

/**
 * Chat types (matches web CHAT_TYPES)
 */
val CHAT_TYPES = listOf(
    ChatTypeOption("Public", "public"),
    ChatTypeOption("Members-only", "group")
)

/**
 * Dialog for creating a new chat - fully matches web NewChatModal
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatDialog(
    onDismiss: () -> Unit,
    onCreateChat: (String, RoomType, String?, String?, List<String>?) -> Unit
) {
    var activeTab by remember { mutableStateOf(0) } // 0 = room creation, 1 = user selection
    var title by remember { mutableStateOf(TextFieldValue("")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }
    var selectedType by remember { mutableStateOf(CHAT_TYPES[0]) } // Default to Public
    var profileImageUri by remember { mutableStateOf<Uri?>(null) }
    var profileImageFile by remember { mutableStateOf<File?>(null) }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var selectedUsers by remember { mutableStateOf<List<RoomMember>>(emptyList()) }
    var showTypeMenu by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            profileImageUri = it
            profileImageFile = uriToFile(context, it)
        }
    }
    
    // Get all users from rooms (similar to web's usersSet)
    val allUsers = remember {
        RoomStore.rooms.value.flatMap { room ->
            room.members ?: emptyList()
        }.distinctBy { it.id }
    }
    
    val isValid = remember(title.text) {
        title.text.length >= 3
    }
    
    fun validateRoomName(name: String): String {
        return if (name.trim().length < 3) {
            "Room name must be at least 3 characters."
        } else {
            ""
        }
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activeTab == 0) "Create New Chat" else "Select users to add to Chat",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }
                
                when (activeTab) {
                    0 -> {
                        // Room creation tab
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Profile image placeholder
                            ProfileImagePlaceholder(
                                imageUri = profileImageUri,
                                onImageClick = { imagePickerLauncher.launch("image/*") },
                                onRemoveClick = {
                                    profileImageUri = null
                                    profileImageFile = null
                                },
                                modifier = Modifier
                                    .size(120.dp)
                                    .align(Alignment.CenterHorizontally)
                            )
                            
                            // Room name
                            OutlinedTextField(
                                value = title,
                                onValueChange = { newValue ->
                                    title = newValue
                                    errors = errors + ("name" to validateRoomName(newValue.text))
                                },
                                label = { Text("Enter Room Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Enter Room Name") },
                                isError = errors["name"]?.isNotEmpty() == true,
                                supportingText = errors["name"]?.let { { Text(it) } }
                            )
                            
                            // Chat type selector
                            ExposedDropdownMenuBox(
                                expanded = showTypeMenu,
                                onExpandedChange = { showTypeMenu = !showTypeMenu }
                            ) {
                                OutlinedTextField(
                                    value = selectedType.name,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Chat Type") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showTypeMenu)
                                    }
                                )
                                ExposedDropdownMenu(
                                    expanded = showTypeMenu,
                                    onDismissRequest = { showTypeMenu = false }
                                ) {
                                    CHAT_TYPES.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type.name) },
                                            onClick = {
                                                selectedType = type
                                                showTypeMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // Description (optional)
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Description (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3,
                                placeholder = { Text("Enter description") }
                            )
                            
                            // Add users button (only for group type)
                            if (selectedType.id == "group") {
                                OutlinedButton(
                                    onClick = { activeTab = 1 },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Add users")
                                }
                            }
                            
                            // Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        val members = if (selectedType.id == "group") {
                                            selectedUsers.mapNotNull { it.xmppUsername }
                                        } else {
                                            null
                                        }
                                        onCreateChat(
                                            title.text.trim(),
                                            if (selectedType.id == "public") RoomType.PUBLIC else RoomType.GROUP,
                                            description.text.takeIf { it.isNotBlank() },
                                            profileImageFile?.absolutePath,
                                            members
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = isValid
                                ) {
                                    Text("Create")
                                }
                            }
                        }
                    }
                    1 -> {
                        // User selection tab
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Select Users (max 20)",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            
                            var searchText by remember { mutableStateOf(TextFieldValue("")) }
                            
                            // Search bar
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search users...") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search"
                                    )
                                },
                                singleLine = true
                            )
                            
                            // Users list
                            val filteredUsers = remember(allUsers, searchText.text) {
                                val query = searchText.text.lowercase()
                                allUsers.filter { user ->
                                    val fullName = "${user.firstName ?: ""} ${user.lastName ?: ""}".trim()
                                    fullName.lowercase().contains(query)
                                }
                            }
                            
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                items(filteredUsers) { user ->
                                    UserSelectionItem(
                                        user = user,
                                        isSelected = selectedUsers.any { it.id == user.id },
                                        onToggle = {
                                            if (selectedUsers.any { it.id == user.id }) {
                                                selectedUsers = selectedUsers.filter { it.id != user.id }
                                            } else {
                                                if (selectedUsers.size < 20) {
                                                    selectedUsers = selectedUsers + user
                                                }
                                            }
                                        },
                                        enabled = selectedUsers.size < 20 || selectedUsers.any { it.id == user.id }
                                    )
                                }
                            }
                            
                            // Back button
                            OutlinedButton(
                                onClick = { activeTab = 0 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Back to creation")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Profile image placeholder component
 */
@Composable
private fun ProfileImagePlaceholder(
    imageUri: Uri?,
    onImageClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onImageClick)
            .border(
                2.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                CircleShape
            )
    ) {
        if (imageUri != null) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Profile image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Remove button overlay
            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .background(
                        MaterialTheme.colorScheme.error,
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove image",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = "Add photo",
                modifier = Modifier
                    .fillMaxSize(0.6f)
                    .align(Alignment.Center),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * User selection item
 */
@Composable
private fun UserSelectionItem(
    user: RoomMember,
    isSelected: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            enabled = enabled
        )
        Text(
            text = "${user.firstName ?: ""} ${user.lastName ?: ""}".trim().takeIf { it.isNotBlank() }
                ?: user.name ?: user.xmppUsername ?: user.id,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Helper to convert URI to File
 */
private fun uriToFile(context: android.content.Context, uri: Uri): File? {
    val fileName = getFileName(context, uri) ?: return null
    val cacheDir = context.cacheDir
    val file = File(cacheDir, fileName)
    
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            java.io.FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return file
    } catch (e: Exception) {
        android.util.Log.e("CreateChatDialog", "Error converting URI to file", e)
        return null
    }
}

/**
 * Helper to get file name from URI
 */
private fun getFileName(context: android.content.Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return cursor.getString(nameIndex)
                }
            }
        }
    }
    return uri.lastPathSegment
}
