package com.ethora.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomMember
import com.ethora.chat.core.search.MESSAGE_SEARCH_DEBOUNCE_MS
import com.ethora.chat.core.search.MESSAGE_SEARCH_MIN_QUERY_LENGTH
import com.ethora.chat.core.search.MESSAGE_SEARCH_PAGE_SIZE
import com.ethora.chat.core.search.MessageSearchHit
import com.ethora.chat.core.search.MessageSearchParams
import com.ethora.chat.core.search.MessageSearchService
import com.ethora.chat.core.search.SearchSender
import com.ethora.chat.core.search.buildSnippet
import com.ethora.chat.core.search.dayEndIso
import com.ethora.chat.core.search.dayStartIso
import com.ethora.chat.core.search.isBackwardsDateRange
import com.ethora.chat.core.search.key
import com.ethora.chat.core.search.matchPeople
import com.ethora.chat.core.search.mergeSearchHits
import com.ethora.chat.core.search.resolveSearchSender
import com.ethora.chat.core.search.xmppLocalPart
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Message search (web MessageSearchModal + useMessageSearch). Debounced,
 * superseded requests are cancelled, results are paged by server offset.
 */
class MessageSearchController(private val scope: CoroutineScope) {

    enum class Status { IDLE, LOADING, LOADING_MORE, DONE, ERROR }

    data class State(
        val status: Status = Status.IDLE,
        val items: List<MessageSearchHit> = emptyList(),
        val total: Int = 0,
        /** Server rows consumed so far: the offset of the next page. */
        val nextOffset: Int = 0
    ) {
        val hasMore: Boolean get() = nextOffset < total
    }

    private data class Request(
        val query: String,
        val chatId: String?,
        val fromUserId: String?,
        val since: String?,
        val until: String?
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var request: Request? = null
    private var job: Job? = null

    /** Called on every change; a request identical to the last one is a no-op. */
    fun update(
        query: String,
        chatId: String?,
        fromUserId: String?,
        since: String?,
        until: String?
    ) {
        val next = Request(query.trim(), chatId, fromUserId, since, until)
        if (next == request) return
        request = next
        job?.cancel()
        if (next.query.length < MESSAGE_SEARCH_MIN_QUERY_LENGTH) {
            job = null
            _state.value = State()
            return
        }
        job = scope.launch {
            delay(MESSAGE_SEARCH_DEBOUNCE_MS)
            runPage(next, offset = 0, append = false)
        }
    }

    fun loadMore() {
        val current = request ?: return
        val state = _state.value
        if (state.status != Status.DONE || !state.hasMore) return
        job?.cancel()
        job = scope.launch { runPage(current, state.nextOffset, append = true) }
    }

    fun retry() {
        val current = request ?: return
        job?.cancel()
        job = scope.launch { runPage(current, offset = 0, append = false) }
    }

    private suspend fun runPage(request: Request, offset: Int, append: Boolean) {
        _state.update { it.copy(status = if (append) Status.LOADING_MORE else Status.LOADING) }
        try {
            val page = MessageSearchService.search(
                MessageSearchParams(
                    q = request.query,
                    chatId = request.chatId,
                    fromUserId = request.fromUserId,
                    since = request.since,
                    until = request.until,
                    limit = MESSAGE_SEARCH_PAGE_SIZE,
                    offset = offset
                )
            )
            _state.update { current ->
                State(
                    status = Status.DONE,
                    items = if (append) mergeSearchHits(current.items, page.items) else page.items,
                    total = page.total,
                    nextOffset = page.nextOffset
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MessageSearch", "Search failed: ${e.message}")
            _state.update { it.copy(status = Status.ERROR) }
        }
    }
}

/** The room a hit belongs to among [rooms], or null when it is not one the user can open here. */
fun messageHitRoom(hit: MessageSearchHit, rooms: List<Room>): Room? =
    rooms.firstOrNull { it.jid == hit.room }
        ?: rooms.firstOrNull { it.jid.substringBefore('@') == hit.chatId }

/** Display names of every member the app knows, by user id and by local JID part. */
private fun memberNames(rooms: List<Room>): Pair<Map<String, String>, Map<String, String>> {
    val byId = HashMap<String, String>()
    val byLocal = HashMap<String, String>()
    for (room in rooms) {
        for (member in room.members.orEmpty()) {
            val name = memberDisplayName(member)
            if (name.isEmpty()) continue
            byId.putIfAbsent(member.id, name)
            member.xmppUsername?.let { byLocal.putIfAbsent(xmppLocalPart(it), name) }
        }
    }
    return byId to byLocal
}

private fun memberDisplayName(member: RoomMember): String =
    "${member.firstName.orEmpty()} ${member.lastName.orEmpty()}".trim().ifEmpty { member.name.orEmpty() }

/** Sender label for a hit: "You", the member's name, or "Someone". [names] comes from [rememberSenderNames]. */
private fun senderLabel(hit: MessageSearchHit, names: SenderNames, myXmppUsername: String?): String =
    when (val sender = resolveSearchSender(hit, names.byUserId, names.byLocalPart, myXmppUsername)) {
        SearchSender.Self -> "You"
        is SearchSender.Named -> sender.name
        SearchSender.Unknown -> "Someone"
    }

internal class SenderNames(val byUserId: Map<String, String>, val byLocalPart: Map<String, String>)

@Composable
internal fun rememberSenderNames(rooms: List<Room>): SenderNames = remember(rooms) {
    val (byId, byLocal) = memberNames(rooms)
    SenderNames(byId, byLocal)
}

private val hitDateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

internal fun formatHitDate(iso: String): String = try {
    hitDateFormat.format(Date(java.time.Instant.parse(iso).toEpochMilli()))
} catch (_: Exception) {
    ""
}

/** The snippet of a hit with the query highlighted. */
@Composable
fun SearchSnippet(body: String, query: String) {
    val text = buildAnnotatedString {
        for (part in buildSnippet(body, query)) {
            if (part.match) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) {
                    append(part.text)
                }
            } else {
                append(part.text)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

/** One search result row. Tapping it opens the message. */
@Composable
fun MessageHitRow(
    hit: MessageSearchHit,
    query: String,
    headline: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatHitDate(hit.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        SearchSnippet(body = hit.body, query = query)
    }
}

/** Date input is typed as YYYY-MM-DD; anything else is ignored until it parses. */
private fun isDayValue(value: String): Boolean = value.isEmpty() || dayStartIso(value) != null

/**
 * Full-screen message search for one room (or all chats). Opened from the
 * chat header. [onOpenHit] receives the hit and the room it belongs to.
 */
@Composable
fun MessageSearchDialog(
    currentRoom: Room,
    rooms: List<Room>,
    myXmppUsername: String?,
    canOpenOtherRooms: Boolean,
    onDismiss: () -> Unit,
    onOpenHit: (MessageSearchHit, Room) -> Unit
) {
    val scope = rememberCoroutineScope()
    val controller = remember { MessageSearchController(scope) }
    val searchState by controller.state.collectAsState()
    val names = rememberSenderNames(rooms)

    var query by remember { mutableStateOf("") }
    var allChats by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var senderQuery by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sinceDay by remember { mutableStateOf("") }
    var untilDay by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val scopeAll = allChats
    val roomName = currentRoom.jid.substringBefore('@')
    val backwards = isBackwardsDateRange(sinceDay, untilDay)
    val since = if (backwards) null else dayStartIso(sinceDay)
    val until = if (backwards) null else dayEndIso(untilDay)
    val activeFilters = listOfNotNull(sender, sinceDay.takeIf { it.isNotEmpty() }, untilDay.takeIf { it.isNotEmpty() }).size

    LaunchedEffect(query, scopeAll, sender?.first, since, until, backwards) {
        controller.update(
            query = if (backwards) "" else query,
            chatId = if (scopeAll) null else roomName,
            fromUserId = sender?.first,
            since = since,
            until = until
        )
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Sender suggestions: this room's members, or everyone when searching all chats.
    val candidates = remember(senderQuery, scopeAll, rooms, currentRoom.jid) {
        if (senderQuery.isBlank()) {
            emptyList()
        } else {
            val pool = if (scopeAll) rooms else listOfNotNull(rooms.firstOrNull { it.jid == currentRoom.jid })
            matchPeople(
                pool.flatMap { room ->
                    room.members.orEmpty().map { member -> member.id to memberDisplayName(member) }
                },
                senderQuery
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
                    }
                    Text(
                        text = "Search messages",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = {
                            Text(if (scopeAll) "Search all chats" else "Search in this chat")
                        },
                        shape = RoundedCornerShape(24.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !scopeAll,
                            onClick = { allChats = false },
                            label = { Text("This chat") }
                        )
                        if (canOpenOtherRooms) {
                            FilterChip(
                                selected = scopeAll,
                                onClick = { allChats = true },
                                label = { Text("All chats") }
                            )
                        }
                    }

                    TextButton(onClick = { filtersOpen = !filtersOpen }) {
                        Text(if (activeFilters > 0) "Filters ($activeFilters)" else "Filters")
                    }

                    if (filtersOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val chosen = sender
                            if (chosen != null) {
                                FilterChip(
                                    selected = true,
                                    onClick = { sender = null },
                                    label = { Text(chosen.second) }
                                )
                            } else {
                                OutlinedTextField(
                                    value = senderQuery,
                                    onValueChange = { senderQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("From") },
                                    placeholder = { Text("Name of the sender") }
                                )
                                candidates.forEach { person ->
                                    TextButton(
                                        onClick = {
                                            sender = person.id to person.name
                                            senderQuery = ""
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(person.name) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = sinceDay,
                                    onValueChange = { if (isDayValue(it)) sinceDay = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("From date") },
                                    placeholder = { Text("YYYY-MM-DD") }
                                )
                                OutlinedTextField(
                                    value = untilDay,
                                    onValueChange = { if (isDayValue(it)) untilDay = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("To date") },
                                    placeholder = { Text("YYYY-MM-DD") }
                                )
                            }
                            if (backwards) {
                                Text(
                                    text = "The start date is after the end date.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (activeFilters > 0) {
                                TextButton(onClick = {
                                    sender = null
                                    senderQuery = ""
                                    sinceDay = ""
                                    untilDay = ""
                                }) { Text("Clear filters") }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    searchHitItems(
                        query = query.trim(),
                        state = searchState,
                        controller = controller,
                        rooms = rooms,
                        names = names,
                        myXmppUsername = myXmppUsername,
                        showRoomHeadline = scopeAll,
                        canOpen = { hit ->
                            messageHitRoom(hit, rooms)?.let { canOpenOtherRooms || it.jid == currentRoom.jid } == true
                        },
                        onOpen = { hit -> messageHitRoom(hit, rooms)?.let { onOpenHit(hit, it) } }
                    )
                }
            }
        }
    }
}

/**
 * Status line, results and "Show more", as list items. Shared by the header
 * dialog and the chat list (where the hits sit under the chat matches).
 */
fun LazyListScope.searchHitItems(
    query: String,
    state: MessageSearchController.State,
    controller: MessageSearchController,
    rooms: List<Room>,
    names: SenderNames,
    myXmppUsername: String?,
    showRoomHeadline: Boolean,
    canOpen: (MessageSearchHit) -> Boolean,
    onOpen: (MessageSearchHit) -> Unit
) {
    val roomTitles = rooms.associate { it.jid to it.title }

    val hintText: String? = when {
        query.length < MESSAGE_SEARCH_MIN_QUERY_LENGTH -> "Type at least $MESSAGE_SEARCH_MIN_QUERY_LENGTH characters"
        state.status == MessageSearchController.Status.LOADING -> "Searching..."
        state.status == MessageSearchController.Status.ERROR -> "Search failed."
        state.status == MessageSearchController.Status.DONE && state.items.isEmpty() -> "No messages found"
        else -> null
    }
    if (hintText != null) {
        item(key = "search-hint") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.status == MessageSearchController.Status.LOADING) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                }
                Text(hintText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.status == MessageSearchController.Status.ERROR) {
                    TextButton(onClick = { controller.retry() }) { Text("Try again") }
                }
            }
        }
    }
    if (state.items.isNotEmpty()) {
        item(key = "search-count") {
            Text(
                text = "${state.total} found",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        items(state.items, key = { it.key() }) { hit ->
            val sender = senderLabel(hit, names, myXmppUsername)
            val headline = if (showRoomHeadline) {
                "${roomTitles[hit.room] ?: "Chat"} \u00b7 $sender"
            } else {
                sender
            }
            MessageHitRow(
                hit = hit,
                query = query,
                headline = headline,
                enabled = canOpen(hit),
                onClick = { onOpen(hit) }
            )
            HorizontalDivider()
        }
        if (state.hasMore) {
            item(key = "search-more") {
                TextButton(
                    onClick = { controller.loadMore() },
                    enabled = state.status != MessageSearchController.Status.LOADING_MORE,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.status == MessageSearchController.Status.LOADING_MORE) "Searching..." else "Show more"
                    )
                }
            }
        }
    }
}
