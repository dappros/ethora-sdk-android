package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.threads.ThreadSupport

/**
 * The thread that is open: its root key (the parent's archive id) and a fallback
 * root for when the root is not in the room's loaded messages (its reference JSON
 * carries the text and the author).
 */
data class ThreadTarget(val key: String, val fallback: Message)

/**
 * Full-screen thread panel, web `ThreadWrapper`: the root, its replies (main list
 * filter is the inverse), a composer with "Also send to <room>", and the reply
 * actions. Opened from the Reply item, the quote, or the reply count pill.
 *
 * Replies are fetched from the archive until the root is reached, 100 per page.
 */
@Composable
fun ThreadDialog(
    room: Room,
    target: ThreadTarget,
    viewModel: ChatRoomViewModel,
    allMessages: List<Message>,
    isMine: (Message) -> Boolean,
    localPartOfCurrentUser: String?,
    reactionsEnabled: Boolean,
    quickReactions: List<String>,
    pickerEnabled: Boolean,
    interactionsEnabled: Boolean,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ThreadContent(
                room = room,
                target = target,
                viewModel = viewModel,
                allMessages = allMessages,
                isMine = isMine,
                localPartOfCurrentUser = localPartOfCurrentUser,
                reactionsEnabled = reactionsEnabled,
                quickReactions = quickReactions,
                pickerEnabled = pickerEnabled,
                interactionsEnabled = interactionsEnabled,
                onClose = onClose
            )
        }
    }
}

@Composable
private fun ThreadContent(
    room: Room,
    target: ThreadTarget,
    viewModel: ChatRoomViewModel,
    allMessages: List<Message>,
    isMine: (Message) -> Boolean,
    localPartOfCurrentUser: String?,
    reactionsEnabled: Boolean,
    quickReactions: List<String>,
    pickerEnabled: Boolean,
    interactionsEnabled: Boolean,
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val threadLoading by viewModel.threadLoading.collectAsState()
    val parentKey = target.key

    val root: Message = remember(allMessages, parentKey, target.fallback) {
        allMessages.firstOrNull { ThreadSupport.threadKey(it) == parentKey || it.id == parentKey }
            ?: target.fallback
    }
    val replies: List<Message> = remember(allMessages, parentKey) {
        allMessages
            .filter { ThreadSupport.isThreadReplyOf(it, parentKey) }
            .sortedBy { it.timestamp ?: it.date.time }
    }

    // Older replies come from the archive until the root is reached.
    LaunchedEffect(parentKey) {
        viewModel.loadThreadHistory(parentKey)
    }

    var menuFor by remember { mutableStateOf<Message?>(null) }
    var menuTap by remember { mutableStateOf(Offset.Zero) }
    var menuBounds by remember { mutableStateOf(Offset.Zero to Offset.Zero) }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    var listSize by remember { mutableStateOf(Offset.Zero) }

    val reactionHandler: (Message) -> ((String) -> Unit)? = { msg ->
        if (reactionsEnabled && interactionsEnabled) {
            val handler: (String) -> Unit = { shortcode -> viewModel.toggleReaction(msg, shortcode) }
            handler
        } else {
            null
        }
    }

    var editingId by remember { mutableStateOf<String?>(null) }
    var composerText by remember { mutableStateOf("") }
    var alsoSendToRoom by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Close thread")
            }
            Text(
                text = "Thread",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp)
            )
            if (threadLoading) {
                Text(
                    text = "Loading replies...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Root message, then replies
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    listOrigin = Offset(pos.x, pos.y)
                    listSize = Offset(coords.size.width.toFloat(), coords.size.height.toFloat())
                }
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "root-${root.id}") {
                    ThreadBubble(
                        message = root,
                        isMine = isMine(root),
                        currentUserLocalPart = localPartOfCurrentUser,
                        reactionsEnabled = reactionsEnabled,
                        interactionsEnabled = interactionsEnabled,
                        viewModel = viewModel,
                        onLongPress = { tap, bounds ->
                            menuTap = tap
                            menuBounds = bounds
                            menuFor = root
                        }
                    )
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                if (replies.isEmpty() && !threadLoading) {
                    item(key = "no-replies") {
                        Text(
                            text = "No replies yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                items(replies, key = { "reply-${it.id}" }) { reply ->
                    ThreadBubble(
                        message = reply,
                        isMine = isMine(reply),
                        currentUserLocalPart = localPartOfCurrentUser,
                        reactionsEnabled = reactionsEnabled,
                        interactionsEnabled = interactionsEnabled,
                        viewModel = viewModel,
                        onLongPress = { tap, bounds ->
                            menuTap = tap
                            menuBounds = bounds
                            menuFor = reply
                        }
                    )
                }
            }

            // Context menu, positioned in this list's coordinates
            menuFor?.let { msg ->
                val relLeft = menuBounds.first.x - listOrigin.x
                val relTop = menuBounds.first.y - listOrigin.y
                val relRight = menuBounds.second.x - listOrigin.x
                val relBottom = menuBounds.second.y - listOrigin.y
                val mineSet: Set<String> = localPartOfCurrentUser
                    ?.let { msg.reaction?.get(it)?.emoji?.toSet() }
                    .orEmpty()
                MessageContextMenu(
                    message = msg,
                    isUser = isMine(msg),
                    visible = true,
                    tapX = menuTap.x - listOrigin.x,
                    tapY = menuTap.y - listOrigin.y,
                    boundsLeft = relLeft,
                    boundsTop = relTop,
                    boundsRight = relRight,
                    boundsBottom = relBottom,
                    containerWidthPx = listSize.x,
                    containerHeightPx = listSize.y,
                    onDismiss = { menuFor = null },
                    onCopy = { clipboard.setText(AnnotatedString(msg.body)) },
                    onEdit = {
                        editingId = msg.id
                        composerText = msg.body
                        menuFor = null
                    },
                    onDelete = {
                        deleteId = msg.id
                        menuFor = null
                    },
                    onResend = {
                        viewModel.resendMessage(msg.id)
                        menuFor = null
                    },
                    quickReactions = if (reactionsEnabled && interactionsEnabled) quickReactions else null,
                    myReactions = mineSet,
                    pickerEnabled = pickerEnabled,
                    onReaction = reactionHandler(msg),
                    onReply = null
                )
            }
        }

        // Composer
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        if (editingId == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = alsoSendToRoom, onCheckedChange = { alsoSendToRoom = it })
                Text(
                    text = "Also send to ${room.name}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editing message",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    editingId = null
                    composerText = ""
                }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel edit")
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = composerText,
                onValueChange = { composerText = it },
                placeholder = { Text("Reply in thread") },
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val text = composerText.trim()
                    if (text.isEmpty()) return@IconButton
                    val editing = editingId
                    if (editing != null) {
                        viewModel.editMessage(editing, text)
                        editingId = null
                    } else {
                        viewModel.sendMessage(text, parentKey, alsoSendToRoom)
                    }
                    composerText = ""
                },
                enabled = composerText.isNotBlank()
            ) {
                Icon(imageVector = Icons.Default.Send, contentDescription = "Send reply")
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete message?") },
            text = { Text("This message will be deleted for everyone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(id)
                    deleteId = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("Cancel") }
            }
        )
    }
}

/** One message in the thread panel: the shared bubble, with the reaction and long-press wiring. */
@Composable
private fun ThreadBubble(
    message: Message,
    isMine: Boolean,
    currentUserLocalPart: String?,
    reactionsEnabled: Boolean,
    interactionsEnabled: Boolean,
    viewModel: ChatRoomViewModel,
    onLongPress: (tap: Offset, bounds: Pair<Offset, Offset>) -> Unit
) {
    val longPress: ((Float, Float, Float, Float, Float, Float) -> Unit)? = if (!interactionsEnabled) {
        null
    } else {
        val handler: (Float, Float, Float, Float, Float, Float) -> Unit = { tapX, tapY, left, top, right, bottom ->
            onLongPress(Offset(tapX, tapY), Offset(left, top) to Offset(right, bottom))
        }
        handler
    }
    val toggle: ((String) -> Unit)? = if (!reactionsEnabled || !interactionsEnabled) {
        null
    } else {
        val handler: (String) -> Unit = { shortcode -> viewModel.toggleReaction(message, shortcode) }
        handler
    }
    MessageBubble(
        message = message,
        isUser = isMine,
        parentMessage = null,
        showAvatar = true,
        showUsername = true,
        showTimestamp = true,
        onLongPress = longPress,
        showReactions = reactionsEnabled,
        onReactionToggle = toggle,
        currentUserLocalPart = currentUserLocalPart,
        onOpenThread = null,
        threadReplyCount = 0
    )
}

/**
 * The thread a message opens: [parentKey] when it is a reply (its parent), else the
 * message's own key. The root comes from the loaded messages, or from the reply's
 * reference JSON when the root is not loaded (web `openThreadForJump` fallback).
 */
internal fun threadTargetFor(message: Message, all: List<Message>, parentKey: String?): ThreadTarget {
    if (parentKey == null) {
        return ThreadTarget(key = ThreadSupport.threadKey(message), fallback = message)
    }
    val loaded = all.firstOrNull { ThreadSupport.threadKey(it) == parentKey || it.id == parentKey }
    if (loaded != null) return ThreadTarget(key = parentKey, fallback = loaded)
    val ref = ThreadSupport.parseMessageReference(message.mainMessage)
    val stub = Message(
        id = parentKey,
        user = com.ethora.chat.core.models.User(id = parentKey, name = ref?.userName),
        date = java.util.Date(),
        body = ref?.text ?: "",
        roomJid = message.roomJid
    )
    return ThreadTarget(key = parentKey, fallback = stub)
}

