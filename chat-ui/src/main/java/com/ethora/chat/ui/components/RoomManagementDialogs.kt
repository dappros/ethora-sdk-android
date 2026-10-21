package com.ethora.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.createRoomFromApi
import com.ethora.chat.core.networking.PublicChat
import com.ethora.chat.core.networking.RoomManagementAPIHelper
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.RoomManagementLogic
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.xmpp.XMPPClient
import com.ethora.chat.ui.i18n.rememberChatTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Public chats directory (web `PublicChatsModal`): GET /v1/chats/public, filter, paging and
 * Join / Open. Joining a chat the user is not in fetches the room by name, adds it to the
 * room list and opens it; the room screen joins it over XMPP like it does for a shared link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicChatsDialog(
    onDismiss: () -> Unit,
    onOpenRoom: (Room) -> Unit
) {
    val t = rememberChatTranslator()
    val scope = rememberCoroutineScope()
    val rooms by RoomStore.rooms.collectAsState()
    val joined = remember(rooms) { RoomManagementLogic.joinedNames(rooms) }

    var items by remember { mutableStateOf<List<PublicChat>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var nextOffset by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(DirectoryStatus.LOADING) }
    var filter by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }

    fun load(offset: Int) {
        status = if (offset == 0) DirectoryStatus.LOADING else DirectoryStatus.LOADING_MORE
        scope.launch {
            try {
                val page = RoomManagementAPIHelper.getPublicChats(offset = offset)
                items = RoomManagementLogic.mergePublicChats(items, page, offset)
                total = page.total
                nextOffset = page.nextOffset
                status = DirectoryStatus.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status = DirectoryStatus.ERROR
            }
        }
    }
    LaunchedEffect(Unit) { load(0) }

    val visible = remember(items, filter) { RoomManagementLogic.filterPublicChats(items, filter) }
    val hasMore = RoomManagementLogic.hasMorePublicChats(nextOffset, total)

    fun open(chat: PublicChat) {
        if (opening != null) return
        val existing = rooms.firstOrNull { it.jid.substringBefore("@") == chat.name }
        if (existing != null) {
            RoomStore.setCurrentRoom(existing)
            onOpenRoom(existing)
            onDismiss()
            return
        }
        opening = chat.name
        scope.launch {
            try {
                val api = RoomManagementAPIHelper.getRoomByName(chat.name)
                val room = createRoomFromApi(api, ChatStore.getEffectiveConference())
                RoomStore.upsertRoom(room)
                RoomStore.setCurrentRoom(room)
                onOpenRoom(room)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status = DirectoryStatus.ERROR
            } finally {
                opening = null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(t("publicChats.title")) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = t("action.close"))
                            }
                        }
                    )
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text(t("publicChats.filter")) },
                        singleLine = true
                    )
                    when {
                        status == DirectoryStatus.LOADING -> CenteredHint {
                            CircularProgressIndicator()
                            Text(t("publicChats.loading"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        status == DirectoryStatus.ERROR -> CenteredHint {
                            Text(t("publicChats.error"), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { load(nextOffset) }) { Text(t("search.messages.retry")) }
                        }
                        visible.isEmpty() -> CenteredHint {
                            Text(
                                if (filter.isNotBlank() && hasMore) t("publicChats.emptyFilterMore") else t("publicChats.empty"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hasMore) {
                                TextButton(onClick = { load(nextOffset) }) { Text(t("search.messages.loadMore")) }
                            }
                        }
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.name }) { chat ->
                                PublicChatRow(
                                    chat = chat,
                                    joined = chat.name in joined,
                                    busy = opening == chat.name,
                                    joinLabel = t("publicChats.join"),
                                    openLabel = t("publicChats.open"),
                                    fallbackTitle = t("search.messages.chat"),
                                    onClick = { open(chat) }
                                )
                                HorizontalDivider()
                            }
                            if (hasMore) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                        OutlinedButton(
                                            onClick = { load(nextOffset) },
                                            enabled = status != DirectoryStatus.LOADING_MORE
                                        ) {
                                            Text(
                                                if (status == DirectoryStatus.LOADING_MORE) t("search.messages.searching")
                                                else t("search.messages.loadMore")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class DirectoryStatus { LOADING, LOADING_MORE, DONE, ERROR }

@Composable
private fun CenteredHint(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) { content() }
}

@Composable
private fun PublicChatRow(
    chat: PublicChat,
    joined: Boolean,
    busy: Boolean,
    joinLabel: String,
    openLabel: String,
    fallbackTitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            if (chat.picture.isNotBlank()) {
                AsyncImage(
                    model = chat.picture,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        RoomManagementLogic.publicChatInitial(chat),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                chat.title.ifBlank { fallbackTitle },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (chat.description.isNotBlank()) {
                Text(
                    chat.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (joined) {
            OutlinedButton(onClick = onClick, enabled = !busy) { Text(openLabel) }
        } else {
            Button(onClick = onClick, enabled = !busy) { Text(joinLabel) }
        }
    }
}

/**
 * Report a chat (web `ModalReportChat`): pick a category; `Other` asks for details. The
 * category id sent to the API is the English one.
 */
@Composable
fun ReportRoomDialog(
    room: Room,
    onDismiss: () -> Unit,
    onResult: (success: Boolean) -> Unit = {}
) {
    val t = rememberChatTranslator()
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf<RoomManagementLogic.ReportCategory?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val isOther = category?.id == RoomManagementLogic.OTHER_CATEGORY.id

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = {
            Text(if (isOther) t("modal.report.messageTitle") else t("modal.report.chatTitle"))
        },
        text = {
            val chosen = category
            if (chosen == null) {
                Column {
                    (RoomManagementLogic.REPORT_CATEGORIES + RoomManagementLogic.OTHER_CATEGORY).forEach { c ->
                        Text(
                            t(c.labelKey),
                            modifier = Modifier.fillMaxWidth().clickable { category = c }.padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isOther) {
                        Text(t("modal.report.otherDetails"))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(t("input.placeholder")) }
                        )
                    } else {
                        Text(t(chosen.labelKey), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {
            if (category != null) {
                TextButton(
                    enabled = !sending && RoomManagementLogic.canSubmitReport(category, text),
                    onClick = {
                        val chosen = category ?: return@TextButton
                        sending = true
                        scope.launch {
                            val ok = try {
                                RoomManagementAPIHelper.reportRoom(
                                    chatName = room.jid.substringBefore("@"),
                                    category = chosen.id,
                                    text = RoomManagementLogic.reportText(chosen, text)
                                )
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                            sending = false
                            onResult(ok)
                            onDismiss()
                        }
                    }
                ) { Text(t("action.report"), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text(t("action.cancel")) }
        }
    )
}

/**
 * Add a member to a group chat (web `AddMembersModal`): POST the membership, send the XMPP
 * invite, then reload the room so the member list is current.
 */
@Composable
fun AddMemberDialog(
    room: Room,
    xmppClient: XMPPClient?,
    onDismiss: () -> Unit,
    onResult: (message: String, success: Boolean) -> Unit
) {
    val t = rememberChatTranslator()
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val normalized = RoomManagementLogic.normalizeMemberInput(userId)

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(t("modal.addMembers.title")) },
        text = {
            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(t("modal.addMembers.userIdPlaceholder")) }
            )
        },
        confirmButton = {
            TextButton(
                enabled = !sending && normalized != null,
                onClick = {
                    val target = normalized ?: return@TextButton
                    sending = true
                    scope.launch {
                        val chatName = room.jid.substringBefore("@")
                        val result = try {
                            RoomManagementAPIHelper.addMembers(chatName, listOf(target))
                            xmppClient?.inviteUser(target, room.jid)
                            refreshRoom(room)
                            t("toast.userAddedToRoom", mapOf("userName" to target)) to true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            t("toast.failedToAddUser") to false
                        }
                        sending = false
                        onResult(result.first, result.second)
                        onDismiss()
                    }
                }
            ) { Text(t("action.add")) }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text(t("action.cancel")) }
        }
    )
}

/** Confirm and remove a member from a group chat (moderators only). */
@Composable
fun RemoveMemberDialog(
    room: Room,
    xmppUsername: String,
    displayName: String,
    onDismiss: () -> Unit,
    onResult: (message: String, success: Boolean) -> Unit
) {
    val t = rememberChatTranslator()
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(t("message.remove")) },
        text = { Text(displayName) },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val result = try {
                            RoomManagementAPIHelper.removeMembers(room.jid.substringBefore("@"), listOf(xmppUsername))
                            refreshRoom(room)
                            t("toast.userRemovedFromRoom", mapOf("userId" to displayName)) to true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            t("toast.error") to false
                        }
                        sending = false
                        onResult(result.first, result.second)
                        onDismiss()
                    }
                }
            ) { Text(t("message.remove"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text(t("action.cancel")) }
        }
    )
}

/** Reloads [room] from the API and stores it, so members and counts are current. */
private suspend fun refreshRoom(room: Room) {
    val api = RoomManagementAPIHelper.getRoomByName(room.jid.substringBefore("@"))
    val fresh = createRoomFromApi(api, ChatStore.getEffectiveConference())
    RoomStore.upsertRoom(
        fresh.copy(
            messages = room.messages,
            lastMessage = room.lastMessage,
            lastMessageTimestamp = room.lastMessageTimestamp,
            lastViewedTimestamp = room.lastViewedTimestamp,
            role = fresh.role ?: room.role
        )
    )
}
