package com.ethora.chat.ui.components

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomMember
import com.ethora.chat.ui.styling.LocalChatThemeOverrides
import java.text.SimpleDateFormat
import java.util.*

/**
 * Chat info screen - displays room information and members
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(
    room: Room,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overrides = LocalChatThemeOverrides.current
    val screenBg = overrides.chatBackground ?: MaterialTheme.colorScheme.background
    val headerBg = overrides.headerBackground ?: MaterialTheme.colorScheme.surface
    val headerContentColor = if (headerBg.luminance() > 0.5f) Color.Black else Color.White
    val cardBg = overrides.outgoingBubbleBackground
        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val cardText = overrides.outgoingBubbleText
        ?: if (cardBg.luminance() > 0.5f) Color.Black else Color.White
    val onScreenBg = if (screenBg.luminance() > 0.5f) Color.Black else Color.White

    Scaffold(
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    val headerText = room.title.ifBlank { room.name.ifBlank { room.jid.substringBefore("@") } }
                    Text(text = headerText, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = headerBg,
                    titleContentColor = headerContentColor,
                    navigationIconContentColor = headerContentColor,
                    actionIconContentColor = headerContentColor
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Room icon and name
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Room icon/avatar
                    Surface(
                        modifier = Modifier.size(128.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 4.dp
                    ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val roomIcon = room.icon
                        if (roomIcon != null && roomIcon.isNotBlank() && roomIcon != "none") {
                            AsyncImage(
                                model = roomIcon,
                                contentDescription = room.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = room.name.take(1).uppercase(),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    }
                    
                    // Room name
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = onScreenBg
                    )

                    // Member count
                    Text(
                        text = "${room.usersCnt} ${if (room.usersCnt == 1) "member" else "members"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onScreenBg.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Description
            val roomDescription = room.description
            if (!roomDescription.isNullOrBlank()) {
                item {
                    InfoCard(
                        label = "Description",
                        value = roomDescription,
                        cardColor = cardBg,
                        textColor = cardText
                    )
                }
            }
            
            // Members list
            val roomMembers = room.members
            if (roomMembers != null && roomMembers.isNotEmpty()) {
                item {
                    Text(
                        text = "Members",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = onScreenBg,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(roomMembers) { member ->
                    MemberItem(member = member, cardColor = cardBg, textColor = cardText)
                }
            }
        }
    }
}

/**
 * Info card component
 */
@Composable
private fun InfoCard(
    label: String,
    value: String,
    cardColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cardColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.7f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
    }
}

/**
 * Member item component
 */
@Composable
private fun MemberItem(
    member: RoomMember,
    cardColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cardColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = textColor.copy(alpha = 0.15f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val firstName = member.firstName
                    val lastName = member.lastName
                    val name = member.name
                    val initials = when {
                        !firstName.isNullOrBlank() -> firstName.first().uppercase()
                        !lastName.isNullOrBlank() -> lastName.first().uppercase()
                        !name.isNullOrBlank() -> name.first().uppercase()
                        else -> member.id.firstOrNull()?.uppercase() ?: "?"
                    }
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = textColor
                    )
                }
            }

            // Name and info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = member.name ?: "${member.firstName ?: ""} ${member.lastName ?: ""}".trim()
                        .takeIf { it.isNotBlank() } ?: member.xmppUsername ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textColor
                )

                if (member.lastActive != null) {
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    val lastActiveValue = member.lastActive
                    if (lastActiveValue != null) {
                        Text(
                            text = dateFormat.format(Date(lastActiveValue * 1000)),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Role badge
            val memberRole = member.role
            if (!memberRole.isNullOrBlank() && memberRole != "none") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (member.banStatus == "banned") {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        textColor.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = memberRole,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (member.banStatus == "banned") {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            textColor
                        }
                    )
                }
            }
        }
    }
}
