package com.ethora.chat.core.store

import com.ethora.chat.core.models.HistoryPreloadState
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.persistence.ChatPersistenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

/**
 * Room store for managing rooms state
 * Matches web Redux store pattern
 * Persists rooms to DataStore (matches web: redux-persist with localStorage)
 */
object RoomStore {
    private const val MAX_UNREAD_COUNT = 99
    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _currentRoom = MutableStateFlow<Room?>(null)
    val currentRoom: StateFlow<Room?> = _currentRoom.asStateFlow()
    
    // Per-room loading states (matches web: rooms[chatJID].isLoading)
    private val _roomLoadingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val roomLoadingStates: StateFlow<Map<String, Boolean>> = _roomLoadingStates.asStateFlow()
    
    // Global loading state (matches web: state.isLoading)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Typing timeout tracking (5 seconds)
    private val typingTimeouts = mutableMapOf<String, MutableMap<String, Job>>()
    private val typingTimeoutDuration = 5000L // 5 seconds

    // Server-supplied `lastViewedTimestamp` values whose room hadn't loaded
    // yet when InitBeforeLoadFlow ran. Previously `setLastViewedTimestamp`
    // silently no-op'd for unknown rooms, so single-chat read markers
    // landed in the void whenever the private-store sync raced the room
    // list fetch (or whenever the server keyed a 1-1 chat under a JID the
    // local RoomStore hadn't materialised yet). Now we cache them and
    // apply on `setRooms` / `addRoom` / `upsertRoom`. Best-effort: the
    // pending entry is consumed on first match.
    private val pendingLastViewedByJid =
        java.util.concurrent.ConcurrentHashMap<String, Long>()
    
    // Persistence manager
    private var persistenceManager: ChatPersistenceManager? = null
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Initialize with persistence manager
     */
    @Synchronized
    fun initialize(persistence: ChatPersistenceManager) {
        if (persistenceManager === persistence) {
            android.util.Log.d("RoomStore", "↻ RoomStore already initialized with this ChatPersistenceManager")
            return
        }
        persistenceManager = persistence
        android.util.Log.d("RoomStore", "✅ RoomStore initialized with ChatPersistenceManager")
    }
    
    /**
     * Load rooms from persistence
     */
    suspend fun loadRoomsFromPersistence(): List<Room> {
        return try {
            val persistence = persistenceManager
            if (persistence != null) {
                val persistedRooms = persistence.loadRooms()
                android.util.Log.d("RoomStore", "📂 Loaded ${persistedRooms.size} rooms from persistence")
                persistedRooms
            } else {
                android.util.Log.w("RoomStore", "⚠️ ChatPersistenceManager not initialized, cannot load from persistence")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomStore", "❌ Error loading rooms from persistence", e)
            emptyList()
        }
    }
    
    /**
     * Load current room JID from persistence
     */
    suspend fun loadCurrentRoomJidFromPersistence(): String? {
        return try {
            val persistence = persistenceManager
            if (persistence != null) {
                persistence.loadCurrentRoomJid()
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("RoomStore", "❌ Error loading current room JID from persistence", e)
            null
        }
    }
    
    /**
     * Persist rooms (background, non-blocking)
     */
    private fun persistRooms() {
        val persistence = persistenceManager ?: return
        persistenceScope.launch {
            try {
                persistence.saveRooms(_rooms.value)
            } catch (e: Exception) {
                android.util.Log.e("RoomStore", "❌ Error persisting rooms", e)
            }
        }
    }
    
    /**
     * Persist current room JID (background, non-blocking)
     */
    private fun persistCurrentRoomJid() {
        val persistence = persistenceManager ?: return
        persistenceScope.launch {
            try {
                persistence.saveCurrentRoomJid(_currentRoom.value?.jid)
            } catch (e: Exception) {
                android.util.Log.e("RoomStore", "❌ Error persisting current room JID", e)
            }
        }
    }

    /**
     * Set rooms
     */
    fun setRooms(rooms: List<Room>) {
        val existingByJid = _rooms.value.associateBy { it.jid }
        _rooms.value = rooms.map { room ->
            mergeSingleRoomPlaceholder(room, existingByJid[room.jid])
        }
        // Persist rooms (background)
        persistRooms()
        // Apply any server-supplied read markers that arrived before these
        // rooms were known locally — see `pendingLastViewedByJid`.
        drainPendingLastViewed()
    }

    /**
     * Add room
     */
    fun addRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val existingIndex = currentRooms.indexOfFirst { it.id == room.id || it.jid == room.jid }
        if (existingIndex == -1) {
            currentRooms.add(room)
        } else {
            currentRooms[existingIndex] = mergeSingleRoomPlaceholder(room, currentRooms[existingIndex])
        }
        _rooms.value = currentRooms
        persistRooms()
        drainPendingLastViewed()
    }

    /**
     * Authoritative update — trusts the incoming [room] as-is (including
     * intentional zero counters). Does NOT run through
     * `mergeSingleRoomPlaceholder`; that helper is only for API-sourced
     * rooms and would revert zero unread/pending back to stale values.
     * `presenceReady` is sticky.
     */
    fun updateRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val index = currentRooms.indexOfFirst { it.id == room.id || it.jid == room.jid }
        if (index >= 0) {
            val existing = currentRooms[index]
            val next = if (room.presenceReady || !existing.presenceReady) {
                room
            } else {
                room.copy(presenceReady = true)
            }
            currentRooms[index] = next
            _rooms.value = currentRooms
            persistRooms()
            if (_currentRoom.value?.id == room.id || _currentRoom.value?.jid == room.jid) {
                _currentRoom.value = next
                persistCurrentRoomJid()
            }
            return
        }
        if (_currentRoom.value?.id == room.id || _currentRoom.value?.jid == room.jid) {
            _currentRoom.value = room
            persistCurrentRoomJid()
        }
    }

    /**
     * Remove room
     */
    fun removeRoom(roomId: String) {
        val currentRooms = _rooms.value.toMutableList()
        currentRooms.removeAll { it.id == roomId || it.jid == roomId }
        _rooms.value = currentRooms
        // Persist rooms (background)
        persistRooms()
        if (_currentRoom.value?.id == roomId || _currentRoom.value?.jid == roomId) {
            _currentRoom.value = null
            // Persist current room JID (background)
            persistCurrentRoomJid()
        }
    }

    /**
     * Set current room. Also recomputes unread counts — web's unreadMiddleware
     * listens for `setCurrentRoom` and zeroes the new-active room's counter
     * immediately. Without this, the room the user just opened would keep its
     * stale unread badge until the next message arrived to trigger a recompute.
     */
    fun setCurrentRoom(room: Room?) {
        val prev = _currentRoom.value

        // While a room is open ChatRoomView keeps lastViewedTimestamp=0.
        // Bump to "now" before recomputing so a list-switch doesn't flash a
        // full badge on the outgoing room.
        if (prev != null && prev.jid != room?.jid && (prev.lastViewedTimestamp ?: 0L) <= 0L) {
            val now = System.currentTimeMillis()
            updateRoom(prev.copy(lastViewedTimestamp = now))
        }

        _currentRoom.value = room
        persistCurrentRoomJid()

        if (room != null) {
            val messagesForRoom = com.ethora.chat.core.store.MessageStore.getMessagesForRoom(room.jid)
            updateUnreadCount(room.jid, messagesForRoom)
        }
        if (prev != null && prev.jid != room?.jid) {
            val messagesForPrev = com.ethora.chat.core.store.MessageStore.getMessagesForRoom(prev.jid)
            updateUnreadCount(prev.jid, messagesForPrev)
        }

        // Strip delimiter on new active, (maybe) insert on backgrounded prev.
        room?.jid?.let { com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(it) }
        if (prev != null && prev.jid != room?.jid) {
            com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(prev.jid)
        }
    }

    /**
     * Get room by ID
     */
    fun getRoomById(roomId: String): Room? {
        return _rooms.value.firstOrNull { it.id == roomId }
    }
    
    /**
     * Get room by JID
     */
    fun getRoomByJid(roomJid: String): Room? {
        return _rooms.value.firstOrNull { it.jid == roomJid }
    }

    fun upsertRoom(room: Room) {
        val currentRooms = _rooms.value.toMutableList()
        val index = currentRooms.indexOfFirst { it.jid == room.jid || it.id == room.id }
        if (index >= 0) {
            currentRooms[index] = mergeSingleRoomPlaceholder(room, currentRooms[index])
        } else {
            currentRooms.add(room)
        }
        _rooms.value = currentRooms
        persistRooms()
        drainPendingLastViewed()
    }
    
    /**
     * Set loading state for a specific room (matches web: setIsLoading({ chatJID, loading }))
     */
    fun setRoomLoading(roomJid: String, loading: Boolean) {
        val currentStates = _roomLoadingStates.value.toMutableMap()
        currentStates[roomJid] = loading
        _roomLoadingStates.value = currentStates
        setHistoryPreloadState(
            roomJid,
            if (loading) HistoryPreloadState.LOADING else HistoryPreloadState.IDLE
        )
    }
    
    /**
     * Get loading state for a specific room
     */
    fun isRoomLoading(roomJid: String): Boolean {
        return _roomLoadingStates.value[roomJid] ?: false
    }
    
    /**
     * Set global loading state (matches web: setIsLoading({ loading }))
     */
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    /**
     * Set composing state for a room (matches web: setComposing)
     * Tracks multiple users typing by adding/removing them from the composingList
     * Auto-clears typing indicators after 5 seconds if no update received
     */
    fun setComposing(roomJid: String, isComposing: Boolean, composingList: List<String>) {
        val room = getRoomByJid(roomJid)
        room?.let {
            // Get the current composing list
            val currentList = it.composingList?.toMutableList() ?: mutableListOf()
            
            // Ensure room has an entry in typingTimeouts map
            if (!typingTimeouts.containsKey(roomJid)) {
                typingTimeouts[roomJid] = mutableMapOf()
            }
            
            // Add or remove users from the list
            composingList.forEach { userName ->
                if (isComposing) {
                    // Add user if not already in the list
                    if (!currentList.contains(userName)) {
                        currentList.add(userName)
                    }
                    
                    // Cancel existing timeout for this user
                    typingTimeouts[roomJid]?.get(userName)?.cancel()
                    
                    // Start a new timeout to auto-remove this user after 5 seconds
                    val timeoutJob = persistenceScope.launch {
                        delay(typingTimeoutDuration)
                        // Auto-remove user from typing list after timeout
                        setComposing(roomJid, false, listOf(userName))
                        android.util.Log.d("RoomStore", "⏱️ Auto-cleared typing indicator for $userName in $roomJid")
                    }
                    typingTimeouts[roomJid]?.put(userName, timeoutJob)
                    
                } else {
                    // Remove user from the list
                    currentList.remove(userName)
                    
                    // Cancel the timeout for this user
                    typingTimeouts[roomJid]?.get(userName)?.cancel()
                    typingTimeouts[roomJid]?.remove(userName)
                }
            }
            
            // Update the room with the new composing state
            val updatedRoom = it.copy(
                composing = currentList.isNotEmpty(),
                composingList = currentList.toList()
            )
            updateRoom(updatedRoom)
        }
    }
    
    /**
     * Set last viewed timestamp for a room (matches web: setLastViewedTimestamp)
     * When a room is opened, set to 0 to mark all messages as read
     * When a room is closed, set to current timestamp
     */
    fun setLastViewedTimestamp(roomJid: String, timestamp: Long) {
        val room = getRoomByJid(roomJid)
        if (room == null) {
            // Park the timestamp until the room shows up — see
            // `pendingLastViewedByJid`. Keep the freshest server value
            // when multiple writes arrive for the same unknown room.
            pendingLastViewedByJid.merge(roomJid, timestamp) { a, b -> maxOf(a, b) }
            android.util.Log.d(
                "RoomStore",
                "📅 lastViewedTimestamp=$timestamp parked for not-yet-loaded room=$roomJid"
            )
            return
        }
        // Reset BOTH unreadMessages and unreadCapped so the per-room dot
        // AND the "10+" flag disappear atomically. Web zeroes both via
        // the unreadMiddleware setLastViewedTimestamp action.
        val updatedRoom = room.copy(
            lastViewedTimestamp = timestamp,
            unreadMessages = 0,
            unreadCapped = false
        )
        updateRoom(updatedRoom)
        com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(roomJid)
        android.util.Log.d("RoomStore", "📅 lastViewedTimestamp=$timestamp + unread zeroed room=$roomJid")
    }

    /**
     * Drain any [pendingLastViewedByJid] entries whose room is now present
     * in the store. Called from every code path that materialises rooms
     * ([setRooms], [addRoom], [upsertRoom]). The pending entry is removed
     * on first apply; rooms re-loaded later (e.g. through pagination)
     * won't redundantly re-zero the unread counter.
     */
    private fun drainPendingLastViewed() {
        if (pendingLastViewedByJid.isEmpty()) return
        val known = _rooms.value.associateBy { it.jid }
        val drained = mutableListOf<String>()
        pendingLastViewedByJid.forEach { (jid, ts) ->
            val room = known[jid] ?: return@forEach
            val current = room.lastViewedTimestamp ?: 0L
            // Don't clobber a fresher local read marker the user may have
            // already produced in-app.
            if (current > 0L && current >= ts) {
                drained.add(jid)
                return@forEach
            }
            val updated = room.copy(
                lastViewedTimestamp = ts,
                unreadMessages = 0,
                unreadCapped = false
            )
            updateRoom(updated)
            com.ethora.chat.core.store.MessageStore.renormalizeRoomDelimiter(jid)
            drained.add(jid)
            android.util.Log.d(
                "RoomStore",
                "📅 drained parked lastViewedTimestamp=$ts onto newly-materialised room=$jid"
            )
        }
        drained.forEach { pendingLastViewedByJid.remove(it) }
    }
    
    /**
     * Update pending messages count for a room
     */
    fun updatePendingCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid)
        if (room != null) {
            val pendingCount = messages.count { it.pending == true }
            if (room.pendingMessages != pendingCount) {
                val updatedRoom = room.copy(pendingMessages = pendingCount)
                updateRoom(updatedRoom)
                android.util.Log.d("RoomStore", "📊 Updated pending count for $roomJid: $pendingCount")
            }
        }
    }
    
    /**
     * Calculate and update unread messages count for a room.
     * One-for-one port of `web/src/roomStore/Middleware/unreadMidlleware.tsx`
     * `computeUnreadForRoom`:
     *   • Active room → always 0 (the user is looking at it).
     *   • Non-active + lastViewed missing-or-zero → EVERY countable message is
     *     unread (fresh room, never opened). Previous Android logic treated
     *     this branch as "all read" which is why the chat-tab badge went to 0
     *     immediately and the per-room dot never appeared on first app open.
     *   • Non-active + lastViewed > 0 → count countable messages whose
     *     timestamp > lastViewed.
     *
     * "Countable" excludes:
     *   - the synthetic "New Messages" delimiter row
     *   - system messages
     *   - deleted-tombstone rows
     *   - any own-user message in ANY in-flight or terminal-failure state
     *     (`pending`, `sendFailed`) AND any echoed own message — own
     *     messages must NEVER count as unread regardless of status.
     *
     * The own-message check goes through the multi-field `isOwnMessage`
     * helper. Previous code compared only `msg.user.id.substringBefore("@")`
     * to the current user's `xmppUsername.substringBefore("@")`. That fails
     * for optimistic / send-failed rows because they carry `user = currentUser`
     * (so `user.id` is the Ethora user id, not the XMPP local part) — those
     * own-user failures were leaking into the unread counter.
     */
    fun updateUnreadCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid) ?: return
        val activeJid = _currentRoom.value?.jid

        // Active room → always 0 unread
        if (roomJid == activeJid) {
            if (room.unreadMessages != 0 || room.unreadCapped) {
                updateRoom(room.copy(unreadMessages = 0, unreadCapped = false))
            }
            return
        }

        val currentUser = UserStore.currentUser.value
        val lastViewed = room.lastViewedTimestamp ?: 0L

        val countable = messages.count { msg ->
            if (msg.id == "delimiter-new") return@count false
            if (msg.pending == true) return@count false
            if (msg.sendFailed == true) return@count false
            if (msg.isDeleted == true) return@count false
            if (msg.isSystemMessage == "true") return@count false

            // Skip every flavour of "this is from me" — the helper checks
            // user.id, user.xmppUsername, user.userJID, xmppFrom against the
            // current user's id / xmppUsername / userJID / username. Covers
            // the mismatch between optimistic rows (user.id = Ethora id) and
            // server echoes (user.id = XMPP local part) that the old single-
            // field check would miss.
            if (isOwnMessage(msg, currentUser)) return@count false

            val ts = msg.timestamp ?: msg.date.time
            if (ts <= 0) return@count false
            if (lastViewed <= 0) return@count true
            ts > lastViewed
        }

        val unread = countable.coerceAtMost(MAX_UNREAD_COUNT)
        val capped = countable > MAX_UNREAD_COUNT

        if ((room.unreadMessages) != unread || room.unreadCapped != capped) {
            updateRoom(room.copy(unreadMessages = unread, unreadCapped = capped))
            android.util.Log.d("RoomStore", "📊 Unread count for $roomJid: $unread (capped=$capped)")
        }
    }

    fun recomputeUnreadForAllRooms() {
        _rooms.value.forEach { room ->
            updateUnreadCount(room.jid, com.ethora.chat.core.store.MessageStore.getMessagesForRoom(room.jid))
        }
    }

    private fun isOwnMessage(
        message: com.ethora.chat.core.models.Message,
        currentUser: com.ethora.chat.core.models.User?
    ): Boolean {
        currentUser ?: return false
        val currentCandidates = identityCandidates(
            currentUser.id,
            currentUser.xmppUsername,
            currentUser.userJID,
            currentUser.username
        )
        if (currentCandidates.isEmpty()) return false

        val messageCandidates = identityCandidates(
            message.user.id,
            message.user.xmppUsername,
            message.xmppFrom
        )
        return messageCandidates.any { it in currentCandidates }
    }

    private fun identityCandidates(vararg rawValues: String?): Set<String> {
        return rawValues.asSequence()
            .filterNotNull()
            .flatMap { value ->
                sequence {
                    val trimmed = value.trim()
                    if (trimmed.isBlank()) return@sequence
                    yield(trimmed.lowercase())
                    val bare = trimmed.substringBefore("/").lowercase()
                    yield(bare)
                    val local = bare.substringBefore("@")
                    if (local.isNotBlank()) yield(local)
                }
            }
            .toSet()
    }

    fun setHistoryPreloadState(roomJid: String, state: HistoryPreloadState) {
        val room = getRoomByJid(roomJid) ?: return
        if (room.historyPreloadState == state) return
        updateRoom(room.copy(historyPreloadState = state))
    }
    
    /**
     * Clear all rooms
     */
    fun clear() {
        _rooms.value = emptyList()
        _currentRoom.value = null
        _roomLoadingStates.value = emptyMap()
        _isLoading.value = false
        pendingLastViewedByJid.clear()
        // Persist cleared state (background)
        persistenceScope.launch {
            persistenceManager?.saveRooms(emptyList())
            persistenceManager?.saveCurrentRoomJid(null)
        }
    }
    
    /**
     * Get persistence manager (for LogoutService)
     */
    internal fun getPersistenceManager(): ChatPersistenceManager? {
        return persistenceManager
    }
}
