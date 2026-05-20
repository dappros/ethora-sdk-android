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
     * Compute the read marker that should be flushed when the user leaves a
     * room — advance to the latest message the SDK knows about for that
     * room, NOT to `System.currentTimeMillis()`.
     *
     * Why: writing `now()` unconditionally marks every existing message as
     * "read" even when the user merely tab-swapped through the chat without
     * scrolling. That breaks host-side unread previews that observe
     * `Room.lastViewedTimestamp` to render their own "what's new" list (e.g.
     * an inbox screen rendered outside the `Chat` composable). The host
     * sees the baseline reset to "now", filters by `ts > now`, and finds
     * nothing — even when the server still has the room flagged as unread.
     *
     * The latest-known-message timestamp matches what the user could
     * actually have seen on screen: if the latest message is older than
     * the existing marker, this is a no-op (return existing); if newer,
     * we advance only to that message's ts, leaving any unread that hasn't
     * arrived yet (or won't arrive in this session) intact.
     */
    fun resolveReadMarkerOnLeave(roomJid: String): Long {
        val room = getRoomByJid(roomJid) ?: return 0L
        val existing = room.lastViewedTimestamp ?: 0L
        val fromRoom = room.lastMessageTimestamp ?: 0L
        val fromStore = MessageStore.lastKnownTimestamp(roomJid)
        val latest = maxOf(fromRoom, fromStore)
        return if (latest > existing) latest else existing
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
        //
        // Also raise the unread baseline to the new lastViewed value so
        // a subsequent recompute (e.g. when historic messages arrive via
        // MAM with timestamps older than the new marker) doesn't backfill
        // the badge. Web parity: the room reducer mirrors lastViewed
        // into `unreadBaselineTimestamp` on every update.
        val newBaseline = maxOf(room.unreadBaselineTimestamp ?: 0L, timestamp)
        val updatedRoom = room.copy(
            lastViewedTimestamp = timestamp,
            unreadBaselineTimestamp = newBaseline,
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
                unreadBaselineTimestamp = maxOf(room.unreadBaselineTimestamp ?: 0L, ts),
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
     *
     * One-for-one port of web SDK's `AT(room, _, activeJid, currentUserKey)`
     * (a.k.a. `computeUnreadForRoom`), from `@ethora/chat-component@26.3.20`:
     *
     * ```js
     * AT = (e, t, n, i) => {
     *   if (!e) return { unread: 0, unreadCapped: false };
     *   if (n === t) return { unread: 0, unreadCapped: false };
     *   const r = Xe(e.lastViewedTimestamp);
     *   const s = Xe(e.unreadBaselineTimestamp);
     *   const a = r > 0 ? r : s;
     *   const o = (e.messages || []).filter((u) => {
     *     if (!oT(u) || ($c(u?.user?.id) !== "" && $c(u?.user?.id) === $c(i))) return false;
     *     const l = l0(u);
     *     return l <= 0 || a <= 0 ? false : l > a;
     *   });
     *   ...
     * }
     * // helpers:
     * oT = (m) => !!m && m.id !== "delimiter-new" && !m.pending && String(m?.isSystemMessage || "") !== "true"
     * $c = (s) => s ? String(s).split("@")[0] : ""
     * l0 = (m) => Xe(m?.date) || Xe(m?.timestamp) || Xe(m?.id)
     * ```
     *
     * Key contract differences from the previous Android implementation:
     *   • Web returns `unread = 0` when both `lastViewedTimestamp` and
     *     `unreadBaselineTimestamp` are zero (fresh room, no read marker
     *     anywhere). The old Android code returned ALL messages as unread
     *     in that branch, which surfaced as a wrong "everything is unread"
     *     badge on rooms the user had never opened — and never recovered
     *     until the user opened-and-closed each one to plant a real
     *     lastViewed marker. The current code now mirrors web: no marker
     *     → no unread.
     *   • Web uses `unreadBaselineTimestamp` as the secondary baseline
     *     (typically synced from the server's private store on app
     *     launch). The new [Room.unreadBaselineTimestamp] field carries
     *     it; falls back to `lastViewedTimestamp` until the server-side
     *     read marker is loaded.
     *   • Strict ">" inequality (web's `l > a`) is preserved — a message
     *     whose timestamp equals the baseline is NOT unread.
     *
     * Own-message detection uses [isOwnMessage] (multi-field identity
     * match across `user.id`, `user.xmppUsername`, `user.userJID`,
     * `xmppFrom`). Optimistic outgoing rows carry the Ethora user id in
     * `user.id`, server echoes carry the XMPP local part — a single-field
     * comparison would let one of those slip through.
     *
     * `sendFailed` and `isDeleted` are filtered explicitly (web hides
     * them at the render layer; we hide them at the count layer for the
     * same effect on the badge).
     */
    fun updateUnreadCount(roomJid: String, messages: List<com.ethora.chat.core.models.Message>) {
        val room = getRoomByJid(roomJid) ?: return
        val activeJid = _currentRoom.value?.jid

        // Active room → always 0 unread. Web: `n === t`.
        if (roomJid == activeJid) {
            if (room.unreadMessages != 0 || room.unreadCapped) {
                updateRoom(room.copy(unreadMessages = 0, unreadCapped = false))
            }
            android.util.Log.d("RoomStoreUnreadDbg", "shortcut: activeJid=$activeJid → unread=0")
            return
        }

        val currentUser = UserStore.currentUser.value
        val lastViewed = room.lastViewedTimestamp ?: 0L
        val baseline = room.unreadBaselineTimestamp ?: 0L
        // Web: `const a = r > 0 ? r : s;`
        val effectiveBaseline = if (lastViewed > 0L) lastViewed else baseline

        android.util.Log.d("RoomStoreUnreadDbg",
            "ENTER room=$roomJid activeJid=$activeJid lastViewed=$lastViewed baseline=$baseline " +
                "effectiveBaseline=$effectiveBaseline msgs=${messages.size} " +
                "currentUser={id=${currentUser?.id}, xmppUsername=${currentUser?.xmppUsername}, " +
                "userJID=${currentUser?.userJID}, username=${currentUser?.username}}")

        val countable = messages.count { msg ->
            // Web's `oT(u)` predicate — plus our own extensions for sendFailed
            // and isDeleted which web hides at render time.
            val skipReason: String = when {
                msg.id == "delimiter-new" -> "delimiter"
                msg.pending == true -> "pending"
                msg.sendFailed == true -> "sendFailed"
                msg.isDeleted == true -> "deleted"
                msg.isSystemMessage == "true" -> "system"
                isOwnMessage(msg, currentUser) -> "ownMessage"
                else -> ""
            }
            if (skipReason.isNotEmpty()) {
                android.util.Log.d("RoomStoreUnreadDbg",
                    "  msg id=${msg.id} body='${msg.body.take(30)}' user={id=${msg.user.id}, " +
                        "xmpp=${msg.user.xmppUsername}, jid=${msg.user.userJID}} " +
                        "xmppFrom=${msg.xmppFrom} → SKIP ($skipReason)")
                return@count false
            }

            // Web: `const l = l0(u); return l <= 0 || a <= 0 ? false : l > a;`
            val ts = msg.timestamp ?: msg.date.time
            val tsReason: String = when {
                ts <= 0L -> "ts<=0"
                effectiveBaseline <= 0L -> "baseline<=0"
                ts <= effectiveBaseline -> "ts<=baseline (diff=${effectiveBaseline - ts}ms)"
                else -> ""
            }
            val passes = tsReason.isEmpty()
            android.util.Log.d("RoomStoreUnreadDbg",
                "  msg id=${msg.id} body='${msg.body.take(30)}' user={id=${msg.user.id}, " +
                    "xmpp=${msg.user.xmppUsername}} ts=$ts dateTime=${msg.date.time} " +
                    "effectiveBaseline=$effectiveBaseline → ${if (passes) "COUNT" else "SKIP ($tsReason)"}")
            passes
        }

        val unread = countable.coerceAtMost(MAX_UNREAD_COUNT)
        val capped = countable > MAX_UNREAD_COUNT
        android.util.Log.d("RoomStoreUnreadDbg",
            "RESULT room=$roomJid countable=$countable unread=$unread (prev=${room.unreadMessages}) capped=$capped")

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

        // For MUC stanzas `xmppFrom` is `roomJid/senderJidOrNick(/optionalRes)`.
        // Passing it as-is to `identityCandidates` would add the ROOM JID (and
        // its local part) as a sender candidate, which can falsely match the
        // current user when the room JID encodes the creator's user id (Ethora
        // rooms are `<creatorId>_<uuid>@conference…`). When the room JID
        // collides with the current user (e.g. user opens a 1-1 chat with
        // themselves, or the host accidentally configures the wrong user
        // record), every incoming MUC stanza would be classified as "own" and
        // the unread badge would stay pinned at zero — exactly the symptom in
        // the field report.
        //
        // The XMPP parser already populates `user.id` and `user.xmppUsername`
        // from the senderJID / from-resource (XMPPWebSocketConnection
        // L864-879), so dropping the room half of `xmppFrom` loses no
        // information. Keep the resource segment as a tertiary candidate for
        // the case where `user.id` is blank (legacy delegate paths).
        val xmppFromSender = message.xmppFrom?.let { from ->
            val slashIdx = from.indexOf('/')
            if (slashIdx >= 0) from.substring(slashIdx + 1) else from
        }?.takeIf { it.isNotBlank() }
        val messageCandidates = identityCandidates(
            message.user.id,
            message.user.xmppUsername,
            xmppFromSender
        )
        val match = messageCandidates.any { it in currentCandidates }
        if (match) {
            // Cheap visibility into the false-positive class. Paired with
            // `RoomStoreUnreadDbg` lines above — when an incoming message in a
            // non-active room is unexpectedly classified as "own" this prints
            // exactly which candidate matched, so host integrations can spot
            // a misconfigured `currentUser` immediately.
            val overlap = messageCandidates.filter { it in currentCandidates }
            android.util.Log.d(
                "RoomStoreUnreadDbg",
                "  → isOwnMessage=true matched=$overlap msgUser={id=${message.user.id}, xmpp=${message.user.xmppUsername}, from=${message.xmppFrom}}"
            )
        }
        return match
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
