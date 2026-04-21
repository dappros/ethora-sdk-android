package com.ethora.chat.core.xmpp

import android.util.Log
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.networking.DnsFallback
import com.ethora.chat.core.models.User
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile

/**
 * WebSocket-based XMPP connection implementation
 * Similar to Swift XMPPStream_WebSocket
 */
class XMPPWebSocketConnection(
    private val wsUrl: String,
    private val username: String,
    private val password: String,
    private val host: String,
    private val conference: String,
    private val resource: String = "default",
    /** DNS fallback overrides (host -> IP). Pass from config so DNS is fixed at connection build time. */
    private val dnsFallbackOverrides: Map<String, String>? = null
) {
    private val TAG = "XMPPWebSocket"
    private var webSocket: WebSocket? = null
    @Volatile private var isConnected: Boolean = false
    @Volatile private var isAuthenticated: Boolean = false
    private val client = OkHttpClient.Builder()
        .dns(DnsFallback.createDns(dnsFallbackOverrides))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        Log.d(TAG, "XMPP WebSocket DNS overrides: ${dnsFallbackOverrides?.size ?: 0} hosts")
    }

    private var delegate: XMPPClientDelegate? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var disconnectRequested: Boolean = false
    
    private var authState: AuthState = AuthState.NOT_STARTED
    private var useLegacyStreamOpen: Boolean = false
    
    enum class AuthState {
        NOT_STARTED,
        STREAM_OPENED,
        FEATURES_RECEIVED,
        AUTHENTICATING,
        SASL_SUCCESS,  // After SASL success, before bind
        BINDING,
        AUTHENTICATED
    }
    
    fun setDelegate(delegate: XMPPClientDelegate?) {
        this.delegate = delegate
    }
    
    // Temporary wrapper to match XMPPClientDelegate interface
    private var clientWrapper: XMPPClient? = null
    fun setClientWrapper(client: XMPPClient) {
        this.clientWrapper = client
    }
    
    // MAM query collectors
    private val mamCollectors = mutableMapOf<String, (XMPPStanza) -> Unit>()
    private val mamQueryComplete = mutableSetOf<String>()
    
    fun registerMAMCollector(queryId: String, collector: (XMPPStanza) -> Unit) {
        mamCollectors[queryId] = collector
        mamQueryComplete.remove(queryId)
        Log.d(TAG, "📋 Registered MAM collector for query: $queryId")
    }
    
    fun unregisterMAMCollector(queryId: String) {
        mamCollectors.remove(queryId)
        mamQueryComplete.remove(queryId)
        Log.d(TAG, "📋 Unregistered MAM collector for query: $queryId")
    }
    
    fun isMAMQueryComplete(queryId: String): Boolean {
        return mamQueryComplete.contains(queryId)
    }
    
    fun clearMAMQueryStatus(queryId: String) {
        mamQueryComplete.remove(queryId)
    }
    
    /**
     * Connect to XMPP server via WebSocket
     */
    suspend fun connect() {
        if (isConnected) {
            Log.w(TAG, "Already connected")
            return
        }
        
        try {
            Log.d(TAG, "🚀 Starting WebSocket connection to: $wsUrl")
            
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Sec-WebSocket-Protocol", "xmpp")
                .build()
            
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ WebSocket opened")
                    isConnected = true
                    scope.launch {
                        sendStreamOpen()
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "📨 Received WebSocket message: ${text.take(200)}")
                    scope.launch {
                        handleIncomingStanza(text)
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "📨 Received WebSocket binary message")
                    scope.launch {
                        handleIncomingStanza(bytes.utf8())
                    }
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket closing: $code - $reason")
                    isConnected = false
                    isAuthenticated = false
                    authState = AuthState.NOT_STARTED
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket closed: $code - $reason")
                    this@XMPPWebSocketConnection.webSocket = null
                    isConnected = false
                    isAuthenticated = false
                    authState = AuthState.NOT_STARTED
                    if (!disconnectRequested) {
                        scope.launch {
                            clientWrapper?.let { delegate?.onXMPPClientDisconnected(it) }
                        }
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket failure", t)
                    this@XMPPWebSocketConnection.webSocket = null
                    isConnected = false
                    isAuthenticated = false
                    authState = AuthState.NOT_STARTED
                    if (!disconnectRequested) {
                        scope.launch {
                            clientWrapper?.let { delegate?.onXMPPClientDisconnected(it) }
                        }
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WebSocket connection", e)
            throw e
        }
    }
    
    /**
     * Send raw XML stanza (public wrapper for sendRaw)
     */
    fun send(xml: String) {
        scope.launch {
            sendRaw(xml)
        }
    }
    
    /**
     * Send stream open (RFC 7395 format: <open> element for WebSocket)
     */
    private suspend fun sendStreamOpen() {
        val streamOpen = if (!useLegacyStreamOpen) {
            // RFC 7395: Use <open> element for WebSocket, not <stream:stream>.
            // Use double quotes only: some servers reject single-quoted attrs (seen as invalid-xml).
            """<open xmlns="urn:ietf:params:xml:ns:xmpp-framing" to="$host" version="1.0"/>"""
        } else {
            // Legacy XMPP stream open (some deployments behind WS gateways still expect this).
            """<stream:stream xmlns="jabber:client" xmlns:stream="http://etherx.jabber.org/streams" to="$host" version="1.0">"""
        }
        sendRaw(streamOpen)
        authState = AuthState.STREAM_OPENED
        Log.d(TAG, "📤 Sent stream open (${if (useLegacyStreamOpen) "legacy" else "RFC7395"}): ${streamOpen.take(200)}")
    }
    
    /**
     * Handle incoming XMPP stanza
     */
    private suspend fun handleIncomingStanza(xml: String) {
        com.ethora.chat.core.store.LogStore.receive(TAG, "📥 Receive: ${xml.take(300)}${if (xml.length > 300) "..." else ""}")
        try {
            Log.d(TAG, "🔍 Handling stanza in state: $authState")
            Log.d(TAG, "   XML: ${xml.take(500)}")
            
            when (authState) {
                AuthState.STREAM_OPENED -> {
                    // If server immediately rejects our framing as invalid XML, retry once with legacy open.
                    if (!useLegacyStreamOpen &&
                        xml.contains("<stream:error") &&
                        (xml.contains("invalid-xml") || xml.contains("invalid_xml"))
                    ) {
                        Log.w(TAG, "⚠️ Server returned <invalid-xml/> after <open/>. Retrying with legacy <stream:stream>.")
                        useLegacyStreamOpen = true
                        // Restart stream on the same socket.
                        sendStreamOpen()
                        return
                    }
                    // Check for server <open> response or stream features
                    if (xml.contains("<open") && xml.contains("urn:ietf:params:xml:ns:xmpp-framing")) {
                        Log.d(TAG, "📥 Received server <open> response")
                        // Extract stream ID if present
                        extractStreamId(xml)
                    }
                    // Expect stream features
                    if (xml.contains("<stream:features") || xml.contains("<features") || 
                        xml.contains("xmlns=\"http://etherx.jabber.org/streams\"") ||
                        xml.contains("xmlns='http://etherx.jabber.org/streams'")) {
                        Log.d(TAG, "📋 Received stream features")
                        // Check if features contain SASL mechanisms (initial auth) or bind (post-SASL)
                        if (xml.contains("mechanisms") && xml.contains("urn:ietf:params:xml:ns:xmpp-sasl")) {
                            // Initial features with SASL mechanisms
                            Log.d(TAG, "   Features contain SASL mechanisms - sending auth")
                            authState = AuthState.FEATURES_RECEIVED
                            sendAuth()
                        } else if (xml.contains("bind") && xml.contains("urn:ietf:params:xml:ns:xmpp-bind")) {
                            // Post-SASL features with bind - need to bind
                            Log.d(TAG, "   Features contain bind - sending resource bind")
                            authState = AuthState.BINDING
                            sendResourceBind()
                        }
                    }
                }
                AuthState.FEATURES_RECEIVED, AuthState.AUTHENTICATING -> {
                    if (xml.contains("<success") || (xml.contains("success") && xml.contains("urn:ietf:params:xml:ns:xmpp-sasl"))) {
                        Log.d(TAG, "✅ SASL Authentication successful!")
                        // After SASL success, send new <open> to restart stream (RFC 7395)
                        authState = AuthState.SASL_SUCCESS
                        sendStreamOpen()
                        // Will receive features again, then need to bind
                    } else if (xml.contains("<failure") || (xml.contains("failure") && xml.contains("urn:ietf:params:xml:ns:xmpp-sasl"))) {
                        Log.e(TAG, "❌ SASL Authentication failed: $xml")
                        authState = AuthState.NOT_STARTED
                        clientWrapper?.let { client ->
                            delegate?.onStatusChanged(client, ConnectionStatus.ERROR)
                        }
                    }
                }
                AuthState.SASL_SUCCESS -> {
                    // After SASL success, we get new stream open and features
                    if (xml.contains("<open") && xml.contains("urn:ietf:params:xml:ns:xmpp-framing")) {
                        Log.d(TAG, "📥 Received server <open> after SASL")
                        extractStreamId(xml)
                    }
                    if (xml.contains("<stream:features") || xml.contains("<features")) {
                        // Post-SASL features should contain bind
                        if (xml.contains("bind") && xml.contains("urn:ietf:params:xml:ns:xmpp-bind")) {
                            Log.d(TAG, "📋 Received features after SASL - sending bind")
                            authState = AuthState.BINDING
                            sendResourceBind()
                        }
                    }
                }
                AuthState.BINDING -> {
                    // Check for bind result
                    if (xml.contains("type=\"result\"") || xml.contains("type='result'")) {
                        if (xml.contains("bind") || xml.contains("urn:ietf:params:xml:ns:xmpp-bind")) {
                            Log.d(TAG, "✅ Resource bind successful!")
                            authState = AuthState.AUTHENTICATED
                            isAuthenticated = true
                            clientWrapper?.let { client ->
                                delegate?.onXMPPClientConnected(client)
                            }
                        }
                    }
                }
                AuthState.AUTHENTICATED -> {
                    // Handle normal XMPP stanzas
                    parseAndHandleStanza(xml)
                }
                else -> {
                    Log.d(TAG, "📨 Received stanza in state: $authState")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling stanza", e)
        }
    }
    
    private var streamId: String? = null
    
    private fun extractStreamId(xml: String) {
        val regex = "id=['\"]([^'\"]+)['\"]".toRegex()
        regex.find(xml)?.groupValues?.get(1)?.let {
            streamId = it
            Log.d(TAG, "   Extracted stream ID: $it")
        }
    }
    
    /**
     * Send resource bind (after SASL success)
     */
    private suspend fun sendResourceBind() {
        val bindId = "bind_${System.currentTimeMillis()}"
        val bindStanza = """<iq type='set' id='$bindId'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><resource>$resource</resource></bind></iq>"""
        sendRaw(bindStanza)
        Log.d(TAG, "📤 Sent resource bind")
    }
    
    /**
     * Send authentication
     */
    private suspend fun sendAuth() {
        val authId = "auth_${System.currentTimeMillis()}"
        val authStanza = """<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='PLAIN'>${encodePlainAuth()}</auth>"""
        sendRaw(authStanza)
        authState = AuthState.AUTHENTICATING
        Log.d(TAG, "🔐 Sent authentication")
    }
    
    /**
     * Encode PLAIN auth (base64 of "\0username\0password")
     */
    private fun encodePlainAuth(): String {
        val authString = "\u0000$username\u0000$password"
        return android.util.Base64.encodeToString(authString.toByteArray(), android.util.Base64.NO_WRAP)
    }
    
    /**
     * Parse and handle XMPP stanza
     */
    private fun parseAndHandleStanza(xml: String) {
        try {
            val stanzaType = extractAttribute(xml, "type")
            val stanzaId = extractAttribute(xml, "id") ?: ""
            
            // Check if this is a MAM result message
            if (xml.contains("urn:xmpp:mam:2") && xml.contains("<result")) {
                Log.d(TAG, "📨 Received MAM result message")
                // Per XEP-0313, <result/> has:
                // - id: the MAM result id (NOT the query id)
                // - queryid: correlation id matching the <query queryid='...'>
                val queryId = extractAttribute(xml, "queryid")

                if (!queryId.isNullOrBlank() && mamCollectors.containsKey(queryId)) {
                    val stanza = XMPPStanza(
                        id = stanzaId,
                        type = "message",
                        from = extractAttribute(xml, "from"),
                        to = extractAttribute(xml, "to"),
                        body = null,
                        xml = xml
                    )
                    mamCollectors[queryId]?.invoke(stanza)
                } else {
                    // Fallback: broadcast to all collectors (some servers may omit queryid)
                    val from = extractAttribute(xml, "from") ?: ""
                    mamCollectors.values.forEach { collector ->
                        val stanza = XMPPStanza(
                            id = stanzaId,
                            type = "message",
                            from = from,
                            to = extractAttribute(xml, "to"),
                            body = null,
                            xml = xml
                        )
                        collector(stanza)
                    }
                }
            }
            
            // Check if this is the final IQ result for MAM query
            if (xml.contains("<iq") && stanzaType == "result") {
                if (stanzaId.startsWith("get-history:")) {
                    Log.d(TAG, "✅ Received MAM query completion IQ: $stanzaId")
                    mamQueryComplete.add(stanzaId)
                }
            }
            
            // Handle room-config stanzas (room creation)
            if (xml.contains("<room-config") || xml.contains("room-config")) {
                parseAndHandleRoomCreation(xml)
                return // Handled
            }
            
            // Handle presence stanzas
            if (xml.contains("<presence")) {
                parseAndHandlePresence(xml)
                return // Handled
            }
            
            // Handle IQ stanzas
            if (xml.contains("<iq")) {
                parseAndHandleIQ(xml)
                // Continue processing as IQ might also contain messages
            }
            
            // Handle message error stanzas (type="error")
            if (xml.contains("<message") && stanzaType == "error") {
                parseAndHandleMessageError(xml)
                return // Handled, don't process as regular message
            }
            
            // Handle chat invite stanzas (contains <invite>)
            if (xml.contains("<message") && xml.contains("<invite")) {
                parseAndHandleChatInvite(xml)
                return // Handled, don't process as regular message
            }
            
            // Handle reaction history from MAM (contains reactions in MAM result)
            if (xml.contains("urn:xmpp:mam:2") && xml.contains("<reactions")) {
                parseAndHandleReactionHistory(xml)
                // Continue processing as it might also be a regular MAM message
            }
            
            // Handle composing/paused stanzas (typing indicators)
            if (xml.contains("<composing") || xml.contains("<paused")) {
                parseAndHandleComposing(xml)
            }
            
            // Handle edit message stanzas (id contains 'edit-message')
            if (xml.contains("<message") && stanzaId.contains("edit-message")) {
                parseAndHandleEditMessage(xml)
                return // Handled, don't process as regular message
            }
            
            // Handle reaction stanzas (id contains 'message-reaction')
            if (xml.contains("<message") && stanzaId.contains("message-reaction")) {
                parseAndHandleReaction(xml)
                return // Handled, don't process as regular message
            }
            
            // Handle delete message stanzas (id='deleteMessageStanza')
            if (xml.contains("<message") && stanzaId == "deleteMessageStanza") {
                parseAndHandleDeleteMessage(xml)
                return // Handled, don't process as regular message
            }
            
            // Handle normal stanzas - parse real-time messages
            // Принимаем type=groupchat ИЛИ отсутствующий type (MUC default), from в формате room/user
            val fromAttr = extractAttribute(xml, "from") ?: ""
            val isMucMessage = fromAttr.contains("/")
            val isRealtimeEligible = xml.contains("<message") &&
                !xml.contains("urn:xmpp:mam:2") &&
                !xml.contains("<composing") && !xml.contains("<paused") &&
                (stanzaType == "groupchat" || stanzaType.isNullOrBlank()) &&
                isMucMessage
            if (isRealtimeEligible) {
                Log.d(TAG, "📥 Incoming real-time message candidate: from=$fromAttr, type=$stanzaType")
                parseAndHandleRealtimeMessage(xml)
            }
            
            // Handle normal stanzas
            val stanza = XMPPStanza(
                id = stanzaId,
                type = stanzaType ?: "message",
                from = extractAttribute(xml, "from"),
                to = extractAttribute(xml, "to"),
                body = extractElementText(xml, "body"),
                xml = xml
            )
            clientWrapper?.let { client ->
                delegate?.onStanzaReceived(client, stanza)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing stanza", e)
        }
    }
    
    /**
     * Parse and handle composing/paused stanza (typing indicator)
     * Matches web: handleComposing in stanzaHandlers.ts
     */
    private fun parseAndHandleComposing(xml: String) {
        try {
            val from = extractAttribute(xml, "from") ?: ""
            val roomJid = from.split("/").firstOrNull() ?: return
            // composingUser part after room JID (room@conference/...); may be empty on some servers
            val composingUser = from.split("/").getOrNull(1) ?: ""
            
            // Get current user's xmppUsername to filter out own typing indicators
            val currentUserXmppUsername = com.ethora.chat.core.store.UserStore.currentUser.value?.xmppUsername
            
            // Don't show own typing indicator.
            // Web compares normalized JIDs without domain & underscores; do the same.
            if (!currentUserXmppUsername.isNullOrBlank()) {
                val composingBare = composingUser.split("@").firstOrNull()?.replace("_", "")?.lowercase()
                val currentBare = currentUserXmppUsername.split("@").firstOrNull()?.replace("_", "")?.lowercase()
                if (!composingBare.isNullOrBlank() && composingBare == currentBare) {
                    return // Ignore own typing indicator
                }
            }
            
            // Check if composing or paused
            val isComposing = xml.contains("<composing")
            
            // Extract fullName from <data> element
            val dataStart = xml.indexOf("<data")
            val dataEnd = xml.indexOf("/>", dataStart)
            val dataXml = if (dataStart != -1 && dataEnd != -1) {
                xml.substring(dataStart, dataEnd + 2)
            } else ""
            
            val fullName = extractAttribute(dataXml, "fullName") ?: "User"
            
            // Build composing list
            val composingList = if (isComposing) {
                listOf(fullName)
            } else {
                emptyList()
            }
            
            // Notify delegate (will update RoomStore)
            clientWrapper?.let { client ->
                delegate?.onComposingReceived(client, roomJid, isComposing, composingList)
            }
            
            Log.d(TAG, "📝 Parsed composing indicator: room=$roomJid, composing=$isComposing, user=$fullName")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing composing stanza", e)
        }
    }
    
    /**
     * Parse and handle real-time message (not from MAM)
     */
    private fun parseAndHandleRealtimeMessage(xml: String) {
        try {
            val messageId = extractAttribute(xml, "id") ?: ""
            // For consistency with MAM history IDs, prefer server archive stanza-id when available.
            val stanzaIdStart = xml.indexOf("<stanza-id")
            val stanzaIdEnd = xml.indexOf(">", stanzaIdStart)
            val stanzaIdXml = if (stanzaIdStart != -1 && stanzaIdEnd != -1) {
                xml.substring(stanzaIdStart, stanzaIdEnd + 1)
            } else ""
            val archiveId = extractAttribute(stanzaIdXml, "id")
            val from = extractAttribute(xml, "from") ?: ""
            val body = extractElementText(xml, "body") ?: ""
            
            // Extract media fields first to check if this is a media message
            val dataStart = xml.indexOf("<data")
            val dataXml = if (dataStart != -1) {
                val dataEnd = xml.indexOf("/>", dataStart)
                if (dataEnd != -1) {
                    xml.substring(dataStart, dataEnd + 2)
                } else {
                    val dataEndTag = xml.indexOf("</data>", dataStart)
                    if (dataEndTag != -1) {
                        xml.substring(dataStart, dataEndTag + 7)
                    } else ""
                }
            } else ""
            
            val isMediafile = extractAttribute(dataXml, "isMediafile")
            val location = extractAttribute(dataXml, "location")
            
            val hasBody = body.isNotBlank()
            val hasMedia = isMediafile == "true" || location != null
            val hasData = dataXml.isNotBlank()
            
            val effectiveMessageId = when {
                !archiveId.isNullOrBlank() -> archiveId
                messageId.isNotBlank() -> messageId
                else -> "msg-${System.currentTimeMillis()}"
            }

            if (!hasBody && !hasMedia && !hasData) {
                Log.d(TAG, "⚠️ Skipping message: no body, media, or data, messageId=$messageId. XML: ${xml.take(500)}")
                return
            }
            
            // Extract room JID from 'from' attribute (format: room@conference.domain/user@domain/resource)
            val roomJid = from.split("/").firstOrNull() ?: return
            
            // Do not drop messages for unknown rooms: room list can be out of sync briefly.
            // We still need to process them for pending reconciliation.
            val roomExists = com.ethora.chat.core.store.RoomStore.getRoomByJid(roomJid) != null
            if (!roomExists) {
                Log.w(TAG, "⚠️ Received message for unknown room: $roomJid, processing anyway")
            }
            
            // Extract user info from 'from' attribute
            val userJid = from.split("/").getOrNull(1) ?: from
            val username = userJid.split("@").firstOrNull() ?: "unknown"
            
            // Extract user info from <data> element if present (already extracted above)
            val senderFirstName = extractAttribute(dataXml, "senderFirstName")
            val senderLastName = extractAttribute(dataXml, "senderLastName")
            val photoURL = extractAttribute(dataXml, "photoURL") ?: extractAttribute(dataXml, "photo")
            val fullName = extractAttribute(dataXml, "fullName")
            val senderJID = extractAttribute(dataXml, "senderJID")
            
            // Extract media fields for media message matching (already extracted above)
            val locationPreview = extractAttribute(dataXml, "locationPreview")
            val fileName = extractAttribute(dataXml, "fileName")
            val mimetype = extractAttribute(dataXml, "mimetype")
            val originalName = extractAttribute(dataXml, "originalName")
            val size = extractAttribute(dataXml, "size")
            val waveForm = extractAttribute(dataXml, "waveForm")
            
            val cleanPhotoURL = photoURL?.takeIf { it.isNotBlank() && it != "none" && it.isNotEmpty() }
            
            // Determine user ID
            val userId = senderJID?.split("@")?.firstOrNull() 
                ?: userJid.split("@")?.firstOrNull() 
                ?: username
            
            // Create User object
            val user = User(
                id = userId,
                firstName = senderFirstName ?: fullName?.split(" ")?.firstOrNull(),
                lastName = senderLastName ?: fullName?.split(" ")?.drop(1)?.joinToString(" "),
                name = fullName,
                profileImage = cleanPhotoURL,
                username = username,
                xmppUsername = senderJID ?: userJid,
                userJID = senderJID ?: userJid
            )
            
            // Check if message is deleted (has <deleted> element)
            val isDeleted = xml.contains("<deleted") || xml.contains("<deleted>")
            
            // Create Message object with media fields
            val message = Message(
                id = effectiveMessageId,
                user = user,
                date = java.util.Date(),
                body = body,
                roomJid = roomJid,
                timestamp = System.currentTimeMillis(),
                xmppId = if (messageId.isNotBlank()) messageId else effectiveMessageId,
                xmppFrom = from,
                pending = false, // Real-time messages are not pending
                isDeleted = isDeleted,
                location = location,
                locationPreview = locationPreview,
                fileName = fileName,
                mimetype = mimetype,
                isMediafile = isMediafile,
                originalName = originalName,
                size = size,
                waveForm = waveForm
            )
            
            // Add to MessageStore with pending reconciliation
            // Matches Swift: first try exact ID match, then fallback to content matching
            // For media messages, also try matching by location/fileName since server might use different ID
            val wasPending = com.ethora.chat.core.store.MessageStore.addMessage(roomJid, message)
            if (!wasPending) {
                // Try content-based matching for all messages (not just media)
                // This handles cases where server echo has different ID than optimistic message
                val matchedPending = com.ethora.chat.core.store.MessageStore.updatePendingMessage(roomJid, message)
                if (matchedPending) {
                    Log.d(TAG, "✅ Matched pending message via content matching")
                } else if (hasMedia || body == "media") {
                    // For media messages, log if no match found
                    Log.d(TAG, "⚠️ No pending message matched for media message $messageId, message added as new")
                }
            } else {
                Log.d(TAG, "✅ Matched pending message via ID matching")
            }
            
            Log.d(TAG, "📨 Parsed real-time message: ID=$effectiveMessageId, stanzaId=$messageId, archiveId=$archiveId, From=$from, Body=${body.take(50)}, isMedia=$hasMedia, wasPending=$wasPending")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing real-time message", e)
        }
    }
    
    private fun extractAttribute(xml: String, attr: String): String? {
        val regex = "$attr=['\"]([^'\"]+)['\"]".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")
    }
    
    private fun extractElementText(xml: String, element: String): String? {
        val startTag = "<$element"
        val startIdx = xml.indexOf(startTag)
        if (startIdx == -1) return null
        
        // Find the end of the start tag (might have attributes)
        val startTagEnd = xml.indexOf(">", startIdx)
        if (startTagEnd == -1) return null
        
        // Check for self-closing tag
        if (xml.getOrNull(startTagEnd - 1) == '/') return ""
        
        val endTag = "</$element>"
        val endIdx = xml.indexOf(endTag, startTagEnd)
        if (endIdx == -1) return null
        
        return xml.substring(startTagEnd + 1, endIdx)
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun resolveBareJid(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.contains("@")) value.substringBefore("/") else "$value@$host"
    }

    private fun resolveOccupantNick(): String {
        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val base = currentUser?.xmppUsername?.substringBefore("@")
            ?: username.substringBefore("@")
        val normalizedBase = base.ifBlank { "user" }.replace("[^A-Za-z0-9._-]".toRegex(), "")
        // Keep a per-connection suffix so the same account can join room from multiple devices.
        return "$normalizedBase-$resource"
    }
    
    /**
     * Parse and handle edit message stanza
     * Matches web: onEditMessage in stanzaHandlers.ts
     */
    private fun parseAndHandleEditMessage(xml: String) {
        try {
            // Extract <replace> element with id and text attributes
            val replaceStart = xml.indexOf("<replace")
            val replaceEnd = xml.indexOf(">", replaceStart)
            if (replaceStart == -1 || replaceEnd == -1) {
                Log.w(TAG, "⚠️ Edit message stanza missing <replace> element")
                return
            }
            
            val replaceXml = xml.substring(replaceStart, replaceEnd + 1)
            val messageId = extractAttribute(replaceXml, "id")
            val newText = extractAttribute(replaceXml, "text")
            
            if (messageId.isNullOrBlank() || newText == null) {
                Log.w(TAG, "⚠️ Edit message stanza missing id or text attribute")
                return
            }
            
            // Extract room JID from stanza-id or from attribute
            val stanzaIdStart = xml.indexOf("<stanza-id")
            val stanzaIdEnd = xml.indexOf(">", stanzaIdStart)
            val stanzaIdXml = if (stanzaIdStart != -1 && stanzaIdEnd != -1) {
                xml.substring(stanzaIdStart, stanzaIdEnd + 1)
            } else ""
            
            val roomJid = extractAttribute(stanzaIdXml, "by") 
                ?: extractAttribute(xml, "from")?.split("/")?.firstOrNull()
                ?: return
            
            Log.d(TAG, "✏️ Edit message received: room=$roomJid, messageId=$messageId, newText=${newText.take(50)}")
            
            // Notify delegate
            clientWrapper?.let { client ->
                delegate?.onMessageEdited(client, roomJid, messageId, newText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing edit message stanza", e)
        }
    }
    
    /**
     * Parse and handle reaction stanza
     * Matches web: onReactionMessage in stanzaHandlers.ts
     */
    private fun parseAndHandleReaction(xml: String) {
        try {
            // Extract <reactions> element
            val reactionsStart = xml.indexOf("<reactions")
            val reactionsEnd = xml.indexOf(">", reactionsStart)
            if (reactionsStart == -1 || reactionsEnd == -1) {
                Log.w(TAG, "⚠️ Reaction stanza missing <reactions> element")
                return
            }
            
            val reactionsXml = xml.substring(reactionsStart, xml.indexOf("</reactions>", reactionsEnd) + "</reactions>".length)
            val messageId = extractAttribute(reactionsXml, "id")
            val from = extractAttribute(reactionsXml, "from")
            
            if (messageId.isNullOrBlank() || from.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Reaction stanza missing id or from attribute")
                return
            }
            
            // Extract reaction emojis
            val reactionElements = mutableListOf<String>()
            var searchStart = reactionsEnd + 1
            while (true) {
                val reactionStart = xml.indexOf("<reaction>", searchStart)
                if (reactionStart == -1) break
                val reactionEnd = xml.indexOf("</reaction>", reactionStart)
                if (reactionEnd == -1) break
                val reactionText = xml.substring(reactionStart + "<reaction>".length, reactionEnd)
                reactionElements.add(reactionText)
                searchStart = reactionEnd + "</reaction>".length
            }
            
            // Extract data element
            val dataStart = xml.indexOf("<data")
            val dataEnd = xml.indexOf("/>", dataStart)
            val dataXml = if (dataStart != -1 && dataEnd != -1) {
                xml.substring(dataStart, dataEnd + 2)
            } else ""
            
            val senderFirstName = extractAttribute(dataXml, "senderFirstName") ?: ""
            val senderLastName = extractAttribute(dataXml, "senderLastName") ?: ""
            val data = mapOf(
                "senderFirstName" to senderFirstName,
                "senderLastName" to senderLastName
            )
            
            // Extract room JID from from attribute
            val roomJid = extractAttribute(xml, "to")?.split("/")?.firstOrNull() ?: return
            
            Log.d(TAG, "😀 Reaction received: room=$roomJid, messageId=$messageId, from=$from, reactions=$reactionElements")
            
            // Notify delegate
            clientWrapper?.let { client ->
                delegate?.onReactionReceived(client, roomJid, messageId, from, reactionElements, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing reaction stanza", e)
        }
    }
    
    /**
     * Parse and handle delete message stanza
     * Matches web: onDeleteMessage in stanzaHandlers.ts
     */
    private fun parseAndHandleDeleteMessage(xml: String) {
        try {
            // Extract <delete> element with id attribute
            val deleteStart = xml.indexOf("<delete")
            val deleteEnd = xml.indexOf(">", deleteStart)
            if (deleteStart == -1 || deleteEnd == -1) {
                Log.w(TAG, "⚠️ Delete message stanza missing <delete> element")
                return
            }
            
            val deleteXml = xml.substring(deleteStart, deleteEnd + 1)
            val deletedMessageId = extractAttribute(deleteXml, "id")
            
            if (deletedMessageId.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Delete message stanza missing id attribute")
                return
            }
            
            // Extract room JID from stanza-id or from attribute
            val stanzaIdStart = xml.indexOf("<stanza-id")
            val stanzaIdEnd = xml.indexOf(">", stanzaIdStart)
            val stanzaIdXml = if (stanzaIdStart != -1 && stanzaIdEnd != -1) {
                xml.substring(stanzaIdStart, stanzaIdEnd + 1)
            } else ""
            
            val roomJid = extractAttribute(stanzaIdXml, "by") 
                ?: extractAttribute(xml, "from")?.split("/")?.firstOrNull()
                ?: return
            
            Log.d(TAG, "🗑️ Delete message received: room=$roomJid, messageId=$deletedMessageId")
            
            // Update message in MessageStore to set isDeleted=true
            val currentMessages = com.ethora.chat.core.store.MessageStore.getMessagesForRoom(roomJid)
            val messageToUpdate = currentMessages.firstOrNull { it.id == deletedMessageId }
            
            if (messageToUpdate != null) {
                val updatedMessage = messageToUpdate.copy(isDeleted = true)
                com.ethora.chat.core.store.MessageStore.updateMessage(roomJid, updatedMessage)
                Log.d(TAG, "✅ Updated message $deletedMessageId to deleted")
            } else {
                Log.w(TAG, "⚠️ Message $deletedMessageId not found in store")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing delete message stanza", e)
        }
    }
    
    /**
     * Send typing indicator (composing/paused)
     * Matches web: sendTypingRequest.xmpp.ts
     */
    suspend fun sendTypingIndicator(roomJid: String, fullName: String, isTyping: Boolean) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send typing indicator: not authenticated")
            return
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }
        
        val id = if (isTyping) "typing-${System.currentTimeMillis()}" else "stop-typing-${System.currentTimeMillis()}"
        val composingElement = if (isTyping) "composing" else "paused"
        val escapedFullName = fullName
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        
        val message = """<message type="groupchat" id="$id" to="$fixedRoomJid"><$composingElement xmlns="http://jabber.org/protocol/chatstates" /><data fullName="$escapedFullName" /></message>"""
        sendRaw(message)
        Log.d(TAG, "📝 Sent typing indicator to $fixedRoomJid: $composingElement (user: $fullName)")
    }
    
    /**
     * Send raw XML
     */
    fun sendRaw(xml: String) {
        com.ethora.chat.core.store.LogStore.send(TAG, "📤 Transmit: ${xml.take(300)}${if (xml.length > 300) "..." else ""}")
        webSocket?.send(xml) ?: run {
            Log.e(TAG, "Cannot send: WebSocket is null")
        }
    }
    
    /**
     * Send message to room
     * Matches web: sendTextMessage.xmpp.ts
     * Returns the message ID that was sent
     */
    suspend fun sendMessage(
        roomJid: String, 
        body: String,
        firstName: String? = null,
        lastName: String? = null,
        photo: String? = null,
        walletAddress: String? = null,
        isReply: Boolean = false,
        showInChannel: Boolean = false,
        mainMessage: String? = null,
        customId: String? = null
    ): String? {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send message: not authenticated")
            return null
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }
        
        // Get current user info
        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val senderFirstName = firstName ?: currentUser?.firstName ?: ""
        val senderLastName = lastName ?: currentUser?.lastName ?: ""
        val fullName = if (senderFirstName.isNotBlank() && senderLastName.isNotBlank()) {
            "$senderFirstName $senderLastName"
        } else {
            currentUser?.fullName ?: senderFirstName.ifBlank { senderLastName }
        }
        val photoURL = photo ?: currentUser?.profileImage ?: ""
        val senderJID = resolveBareJid(currentUser?.userJID)
            ?: resolveBareJid(currentUser?.xmppUsername)
            ?: resolveBareJid(username)
            ?: ""
        val senderWalletAddress = walletAddress ?: ""
        
        val messageId = customId ?: if (isReply) {
            "send-reply-message-${System.currentTimeMillis()}"
        } else {
            "send-text-message-${System.currentTimeMillis()}"
        }
        
        // Escape XML special characters
        val escapedBody = escapeXml(body)
        val escapedFirstName = escapeXml(senderFirstName)
        val escapedLastName = escapeXml(senderLastName)
        val escapedFullName = escapeXml(fullName)
        val escapedPhotoURL = escapeXml(photoURL)
        val escapedSenderJID = escapeXml(senderJID)
        val escapedWalletAddress = escapeXml(senderWalletAddress)
        val escapedRoomJid = escapeXml(fixedRoomJid)
        val escapedMainMessage = mainMessage?.let { escapeXml(it) } ?: ""
        
        // Build data element (matches web version exactly)
        // Web version uses devServer URL as xmlns (unusual but that's what they do)
        // xmlns uses devServer URL (from config)
        val dataElement = """<data xmlns="$wsUrl" senderFirstName="$escapedFirstName" senderLastName="$escapedLastName" fullName="$escapedFullName" photoURL="$escapedPhotoURL" senderJID="$escapedSenderJID" senderWalletAddress="$escapedWalletAddress" roomJid="$escapedRoomJid" isSystemMessage="false" tokenAmount="0" quickReplies="" notDisplayedValue="" showInChannel="${showInChannel}" isReply="${isReply}" mainMessage="$escapedMainMessage" push="true"/>"""
        
        val message = """<message to="$fixedRoomJid" type="groupchat" id="$messageId">$dataElement<body>$escapedBody</body></message>"""
        com.ethora.chat.core.store.LogStore.info(TAG, "Executing sendMessage() to $fixedRoomJid")
        sendRaw(message)
        Log.d(TAG, "📤 Sent message to $fixedRoomJid: $body (ID: $messageId)")
        Log.d(TAG, "📤 Message XML: ${message.take(500)}")
        return messageId
    }
    
    /**
     * Send media message
     * Matches web: sendMediaMessage.xmpp.ts
     */
    suspend fun sendMediaMessage(
        roomJid: String,
        mediaData: Map<String, Any>,
        messageId: String
    ): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send media message: not authenticated")
            return false
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }
        
        // Get current user's JID for from attribute
        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val senderJid = when {
            !currentUser?.userJID.isNullOrBlank() && currentUser?.userJID?.contains("@") == true -> currentUser?.userJID
            !currentUser?.xmppUsername.isNullOrBlank() && currentUser?.xmppUsername?.contains("@") == true -> currentUser?.xmppUsername
            !currentUser?.xmppUsername.isNullOrBlank() -> "${currentUser?.xmppUsername}@$host"
            else -> null
        }
        val fromAttribute = senderJid?.let { """ from="$it"""" } ?: ""
        
        // Build data element XML with all media attributes
        val dataAttributes = mediaData.map { (key, value) ->
            val escapedValue = value.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
            "$key=\"$escapedValue\""
        }.joinToString(" ")
        
        // Escape messageId for XML id attribute
        val safeId = messageId
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        // Matches web version: includes from attribute and store hint
        val message = """<message id="$safeId" type="groupchat"$fromAttribute to="$fixedRoomJid"><body>media</body><store xmlns="urn:xmpp:hints"/><data $dataAttributes/></message>"""
        com.ethora.chat.core.store.LogStore.info(TAG, "Executing sendMediaMessage() to $fixedRoomJid")
        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "❌ Failed to send media message: WebSocket is null (isConnected=$isConnected, isAuthenticated=$isAuthenticated)")
            return false
        }
        var sent = ws.send(message)
        if (!sent) {
            // One retry: socket state can be briefly inconsistent
            kotlinx.coroutines.delay(50)
            val ws2 = webSocket
            if (ws2 != null) {
                sent = ws2.send(message)
            }
        }
        if (!sent) {
            Log.e(TAG, "❌ Failed to send media message: WebSocket.send() returned false (socket may be closed; isConnected=$isConnected)")
            return false
        }
        Log.d(TAG, "📤 Sent media message to $fixedRoomJid")
        return true
    }
    
    /**
     * Send MAM query for message history
     * Matches Swift: SendGetHistory.swift
     * - When beforeMessageId is null: sends RSM <before/> (empty) to get newest messages
     * - When beforeMessageId is provided: sends RSM <before>messageId</before> to paginate older messages
     * Note: Swift uses message ID (Int64) converted from message.id string using Number()
     */
    suspend fun sendMAMQuery(roomJid: String, max: Int, beforeMessageId: String? = null, queryId: String): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send MAM query: not authenticated")
            return false
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }

        // Build RSM <before> element - matches Swift/Web logic
        // If beforeMessageId is null or empty, send <before/> to get newest messages.
        // Otherwise, send <before>ID</before> to get older messages.
        val beforeStanza = if (!beforeMessageId.isNullOrBlank()) {
            "<before>$beforeMessageId</before>"
        } else {
            "<before/>"  // Empty element to request newest messages
        }

        val mamQuery = """
            <iq type='set' to='$fixedRoomJid' id='$queryId'>
              <query xmlns='urn:xmpp:mam:2' queryid='$queryId'>
                <set xmlns='http://jabber.org/protocol/rsm'>
                  <max>$max</max>
                  $beforeStanza
                </set>
              </query>
            </iq>
        """.trimIndent()

        sendRaw(mamQuery)
        Log.d(TAG, "📤 Sent MAM query for $fixedRoomJid (max=$max, before=$beforeMessageId, queryId=$queryId)")
        return true
    }
    
    /**
     * Edit message
     * Matches web: editMessage.xmpp.ts
     */
    suspend fun editMessage(roomJid: String, messageId: String, newText: String) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot edit message: not authenticated")
            return
        }

        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }

        val id = "edit-message-${System.currentTimeMillis()}"
        val escapedText = escapeXml(newText)
        
        val stanza = """<message to="$fixedRoomJid" type="groupchat" id="$id">
            |<replace id="$messageId" text="$escapedText"/>
            |</message>""".trimMargin()
        sendRaw(stanza)
        Log.d(TAG, "✏️ Sent edit message to $fixedRoomJid: messageId=$messageId, newText=${newText.take(50)}")
    }
    
    /**
     * Delete message
     * Matches web: deleteMessage.xmpp.ts
     */
    suspend fun deleteMessage(roomJid: String, messageId: String) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot delete message: not authenticated")
            return
        }

        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }

        val id = "deleteMessageStanza"
        
        val stanza = """<message to="$fixedRoomJid" type="groupchat" id="$id">
            |<body>wow</body>
            |<delete id="$messageId"/>
            |</message>""".trimMargin()
        sendRaw(stanza)
        Log.d(TAG, "🗑️ Sent delete message to $fixedRoomJid: messageId=$messageId")
    }
    
    /**
     * Send message reaction
     * Matches web: sendMessageReaction.xmpp.ts
     */
    suspend fun sendMessageReaction(roomJid: String, messageId: String, reactions: List<String>) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send reaction: not authenticated")
            return
        }

        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }

        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val senderFirstName = currentUser?.firstName ?: ""
        val senderLastName = currentUser?.lastName ?: ""
        val fromJid = resolveBareJid(currentUser?.userJID)
            ?: resolveBareJid(currentUser?.xmppUsername)
            ?: resolveBareJid(username)
            ?: ""
        
        val id = "message-reaction:${System.currentTimeMillis()}"
        
        // Build reactions XML
        val reactionsXml = reactions.joinToString("") { reaction ->
            "<reaction>$reaction</reaction>"
        }
        
        val stanza = """<message type="groupchat" id="$id" from="$fromJid" to="$fixedRoomJid">
            |<reactions id="$messageId" from="$fromJid" xmlns="urn:xmpp:reactions:0">
            |$reactionsXml
            |</reactions>
            |<data senderFirstName="${escapeXml(senderFirstName)}" senderLastName="${escapeXml(senderLastName)}"/>
            |<store xmlns="urn:xmpp:hints"/>
            |</message>""".trimMargin()
        sendRaw(stanza)
        Log.d(TAG, "😀 Sent reaction to $fixedRoomJid: messageId=$messageId, reactions=$reactions")
    }
    
    /**
     * Parse and handle room creation stanza
     * Matches web: onNewRoomCreated in stanzaHandlers.ts
     */
    private fun parseAndHandleRoomCreation(xml: String) {
        try {
            val from = extractAttribute(xml, "from") ?: return
            val roomJid = from.split("/").firstOrNull() ?: from
            
            Log.d(TAG, "🏠 New room created: $roomJid")
            // TODO: Set as current room and fetch rooms from API
            // For now, just log it
            // In web version: store.dispatch(setCurrentRoom({ roomJID: stanza.attrs.from }))
            // Then: xmpp.getRoomsStanza()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing room creation stanza", e)
        }
    }
    
    /**
     * Parse and handle presence stanza
     * Matches web: onPresenceInRoom and onRoomKicked in stanzaHandlers.ts
     */
    private fun parseAndHandlePresence(xml: String) {
        try {
            val from = extractAttribute(xml, "from") ?: ""
            val type = extractAttribute(xml, "type")
            val presenceId = extractAttribute(xml, "id")
            
            // Handle room kicked (presence type="unavailable" with status codes 110 and 321)
            if (type == "unavailable") {
                val hasStatus110 = xml.contains("code=\"110\"") || xml.contains("code='110'")
                val hasStatus321 = xml.contains("code=\"321\"") || xml.contains("code='321'")
                
                if (hasStatus110 && hasStatus321) {
                    val roomJid = from.split("/").firstOrNull() ?: return
                    Log.d(TAG, "🚫 User was kicked from room: $roomJid")
                    // TODO: Notify delegate or remove room from RoomStore
                    // For now, just log it
                    return
                }
            }
            
            // Handle presence in room (id="presenceInRoom")
            if (presenceId == "presenceInRoom" && !xml.contains("<error")) {
                val roomJid = from.split("/").firstOrNull() ?: return
                
                // Extract role from presence
                // Format: <x xmlns="..."><item role="..." /></x>
                val roleMatch = "role=\"([^\"]+)\"".toRegex().find(xml)
                val role = roleMatch?.groupValues?.get(1)
                
                if (role != null) {
                    Log.d(TAG, "👤 Presence in room: $roomJid, role: $role")
                    // TODO: Update room role in RoomStore
                    // For now, just log it
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing presence stanza", e)
        }
    }
    
    /**
     * Parse and handle IQ stanza
     * Matches web: onGetChatRooms, onGetRoomInfo, onGetLastMessageArchive in stanzaHandlers.ts
     */
    private fun parseAndHandleIQ(xml: String) {
        try {
            val iqId = extractAttribute(xml, "id") ?: ""
            val type = extractAttribute(xml, "type") ?: ""
            
            // Route IQ responses to pending collectors (MUC-SUB, etc.)
            if (iqId.isNotEmpty() && mamCollectors.containsKey(iqId)) {
                val stanza = XMPPStanza(type = type, id = iqId, from = extractAttribute(xml, "from"), to = extractAttribute(xml, "to"))
                mamCollectors[iqId]?.invoke(stanza)
            }

            // Handle MAM query completion (contains <fin> element)
            // Matches web: onGetLastMessageArchive in stanzaHandlers.ts
            if (type == "result" && xml.contains("<fin")) {
                parseAndHandleMAMQueryCompletion(xml, iqId)
            }
            
            // Handle get chat rooms (id="getUserRooms")
            if (iqId == "getUserRooms" && type == "result") {
                parseAndHandleGetChatRooms(xml)
            }
            
            // Handle room info (id="roomInfo")
            if (iqId == "roomInfo" && type == "result" && !xml.contains("<error")) {
                Log.d(TAG, "📋 Received room info IQ")
                // TODO: Parse and update room info
            }
            
            // Handle last message archive (id contains "get-history")
            if (iqId.contains("get-history") && type == "result") {
                parseAndHandleLastMessageArchive(xml)
            }
            
            // Mark MAM query as complete (for getHistory tracking)
            if (iqId.contains("get-history") && type == "result") {
                // Mark query as complete in mamQueryComplete set
                mamQueryComplete.add(iqId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing IQ stanza", e)
        }
    }
    
    /**
     * Parse and handle MAM query completion
     * Matches web: onGetLastMessageArchive in stanzaHandlers.ts
     * Sets historyComplete and messageStats in RoomStore
     */
    private fun parseAndHandleMAMQueryCompletion(xml: String, iqId: String) {
        try {
            val from = extractAttribute(xml, "from") ?: return
            val roomJid = from.split("/").firstOrNull() ?: return
            
            val finStart = xml.indexOf("<fin")
            if (finStart == -1) return
            
            val finEnd = xml.indexOf(">", finStart)
            val finXml = xml.substring(finStart, finEnd + 1)
            
            val complete = extractAttribute(finXml, "complete") ?: "false"
            val isComplete = complete == "true"
            
            // Extract set element for messageStats
            val setStart = xml.indexOf("<set")
            var firstTimestamp: Long? = null
            var lastTimestamp: Long? = null
            
            if (setStart != -1) {
                val setEnd = xml.indexOf("</set>", setStart)
                if (setEnd != -1) {
                    val setXml = xml.substring(setStart, setEnd + "</set>".length)
                    val first = extractElementText(setXml, "first") ?: ""
                    val last = extractElementText(setXml, "last") ?: ""
                    
                    firstTimestamp = first.toLongOrNull()
                    lastTimestamp = last.toLongOrNull()
                }
            }
            
            Log.d(TAG, "📚 MAM query completion for $roomJid (IQ ID: $iqId): complete=$isComplete, first=$firstTimestamp, last=$lastTimestamp")
            
            // Update room with historyComplete and messageStats
            val room = com.ethora.chat.core.store.RoomStore.getRoomByJid(roomJid)
            if (room != null) {
                val updatedRoom = room.copy(
                    historyComplete = isComplete,
                    messageStats = com.ethora.chat.core.models.MessageStats(
                        firstMessageTimestamp = firstTimestamp,
                        lastMessageTimestamp = lastTimestamp
                    )
                )
                com.ethora.chat.core.store.RoomStore.updateRoom(updatedRoom)
                Log.d(TAG, "✅ Updated room $roomJid: historyComplete=$isComplete")
            } else {
                Log.w(TAG, "⚠️ Room not found for MAM completion: $roomJid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing MAM query completion", e)
        }
    }
    
    /**
     * Parse and handle get chat rooms IQ
     * Matches web: onGetChatRooms in stanzaHandlers.ts
     */
    private fun parseAndHandleGetChatRooms(xml: String) {
        try {
            // Extract query element
            val queryStart = xml.indexOf("<query")
            if (queryStart == -1) return
            
            // Find all <item> elements in query
            val items = mutableListOf<Map<String, String>>()
            var searchStart = queryStart
            
            while (true) {
                val itemStart = xml.indexOf("<item", searchStart)
                if (itemStart == -1) break
                
                val itemEnd = xml.indexOf("/>", itemStart)
                if (itemEnd == -1) break
                
                val itemXml = xml.substring(itemStart, itemEnd + 2)
                val jid = extractAttribute(itemXml, "jid") ?: ""
                val name = extractAttribute(itemXml, "name") ?: ""
                val usersCnt = extractAttribute(itemXml, "users_cnt") ?: "0"
                val roomBg = extractAttribute(itemXml, "room_background")
                val roomThumbnail = extractAttribute(itemXml, "room_thumbnail")
                
                if (jid.isNotEmpty()) {
                    items.add(mapOf(
                        "jid" to jid,
                        "name" to name,
                        "users_cnt" to usersCnt,
                        "room_background" to (roomBg ?: ""),
                        "room_thumbnail" to (roomThumbnail ?: "")
                    ))
                }
                
                searchStart = itemEnd + 2
            }
            
            Log.d(TAG, "📋 Received ${items.size} chat rooms from IQ")
            // TODO: Add rooms to RoomStore
            // For now, rooms are loaded from API, so this might be redundant
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing get chat rooms IQ", e)
        }
    }
    
    /**
     * Parse and handle last message archive IQ
     * Matches web: onGetLastMessageArchive in stanzaHandlers.ts
     */
    private fun parseAndHandleLastMessageArchive(xml: String) {
        try {
            val from = extractAttribute(xml, "from") ?: return
            val finStart = xml.indexOf("<fin")
            if (finStart == -1) return
            
            val finEnd = xml.indexOf(">", finStart)
            val finXml = xml.substring(finStart, finEnd + 1)
            
            val complete = extractAttribute(finXml, "complete") ?: "false"
            val isComplete = complete == "true"
            
            // Extract set element
            val setStart = xml.indexOf("<set")
            if (setStart != -1) {
                val setEnd = xml.indexOf("</set>", setStart)
                if (setEnd != -1) {
                    val setXml = xml.substring(setStart, setEnd + "</set>".length)
                    val first = extractElementText(setXml, "first") ?: ""
                    val last = extractElementText(setXml, "last") ?: ""
                    
                    val firstTimestamp = first.toLongOrNull() ?: 0L
                    val lastTimestamp = last.toLongOrNull() ?: 0L
                    
                    Log.d(TAG, "📚 Last message archive for $from: complete=$isComplete, first=$firstTimestamp, last=$lastTimestamp")
                    // TODO: Update room messageStats in RoomStore
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing last message archive IQ", e)
        }
    }
    
    /**
     * Parse and handle message error stanza
     * Matches web: onMessageError in stanzaHandlers.ts
     */
    private fun parseAndHandleMessageError(xml: String) {
        try {
            val from = extractAttribute(xml, "from") ?: return
            val roomJid = from.split("/").firstOrNull() ?: return
            
            // Check for "not-authorized" or "forbidden" errors
            val hasNotAuthorized = xml.contains("not-authorized") || xml.contains("not_authorized")
            val hasForbidden = xml.contains("forbidden")
            
            if (hasNotAuthorized || hasForbidden) {
                Log.w(TAG, "⚠️ Message error for room $roomJid: not authorized or forbidden")
                // TODO: Send presence to room and retry queued messages
                // For now, just log it
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing message error stanza", e)
        }
    }
    
    /**
     * Parse and handle chat invite stanza
     * Matches web: onChatInvite in stanzaHandlers.ts
     */
    private fun parseAndHandleChatInvite(xml: String) {
        try {
            val from = extractAttribute(xml, "from") ?: return
            val chatId = from.split("/").firstOrNull() ?: return
            
            // Check if room already exists
            val existingRoom = com.ethora.chat.core.store.RoomStore.getRoomByJid(chatId)
            if (existingRoom != null) {
                Log.d(TAG, "📥 Chat invite received but room already exists: $chatId")
                return
            }
            
            // Extract invite element
            val inviteStart = xml.indexOf("<invite")
            if (inviteStart == -1) return
            
            Log.d(TAG, "📥 Chat invite received for: $chatId")
            // TODO: Send presence to room and fetch rooms from API
            // For now, just log it
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing chat invite stanza", e)
        }
    }
    
    /**
     * Parse and handle reaction history from MAM
     * Matches web: onReactionHistory in stanzaHandlers.ts
     */
    private fun parseAndHandleReactionHistory(xml: String) {
        try {
            // Extract reactions from MAM result
            // Format: <result><forwarded><message><reactions>...</reactions></message></forwarded></result>
            val reactionsStart = xml.indexOf("<reactions")
            if (reactionsStart == -1) return
            
            val reactionsEnd = xml.indexOf("</reactions>", reactionsStart)
            if (reactionsEnd == -1) return
            
            val reactionsXml = xml.substring(reactionsStart, reactionsEnd + "</reactions>".length)
            val messageId = extractAttribute(reactionsXml, "id") ?: return
            val from = extractAttribute(reactionsXml, "from") ?: return
            
            // Extract reaction emojis
            val reactionElements = mutableListOf<String>()
            var searchStart = reactionsEnd + 1
            while (true) {
                val reactionStart = xml.indexOf("<reaction>", searchStart)
                if (reactionStart == -1) break
                val reactionEnd = xml.indexOf("</reaction>", reactionStart)
                if (reactionEnd == -1) break
                val reactionText = xml.substring(reactionStart + "<reaction>".length, reactionEnd)
                reactionElements.add(reactionText)
                searchStart = reactionEnd + "</reaction>".length
            }
            
            // Extract data element
            val dataStart = xml.indexOf("<data")
            val dataEnd = xml.indexOf("/>", dataStart)
            val dataXml = if (dataStart != -1 && dataEnd != -1) {
                xml.substring(dataStart, dataEnd + 2)
            } else ""
            
            val senderFirstName = extractAttribute(dataXml, "senderFirstName") ?: ""
            val senderLastName = extractAttribute(dataXml, "senderLastName") ?: ""
            val data = mapOf(
                "senderFirstName" to senderFirstName,
                "senderLastName" to senderLastName
            )
            
            // Extract room JID from stanza-id
            val stanzaIdStart = xml.indexOf("<stanza-id")
            val stanzaIdEnd = xml.indexOf(">", stanzaIdStart)
            val stanzaIdXml = if (stanzaIdStart != -1 && stanzaIdEnd != -1) {
                xml.substring(stanzaIdStart, stanzaIdEnd + 1)
            } else ""
            
            val roomJid = extractAttribute(stanzaIdXml, "by") 
                ?: extractAttribute(xml, "from")?.split("/")?.firstOrNull()
                ?: return
            
            Log.d(TAG, "😀 Reaction history received: room=$roomJid, messageId=$messageId, from=$from, reactions=$reactionElements")
            
            // Notify delegate (same as real-time reaction)
            clientWrapper?.let { client ->
                delegate?.onReactionReceived(client, roomJid, messageId, from, reactionElements, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing reaction history", e)
        }
    }
    
    /**
     * Escape XML special characters
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    /**
     * Send general presence (available)
     */
    suspend fun sendGeneralPresence(): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send general presence: not authenticated")
            return false
        }
        
        val presence = """<presence/>"""
        sendRaw(presence)
        return true
    }
    
    /**
     * Send presence in room
     */
    suspend fun sendPresenceInRoom(roomJid: String): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send presence: not authenticated")
            return false
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }
        
        // Get current user's local part (username without domain)
        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val localPart = resolveOccupantNick()
        
        // Get full JID for from attribute
        val fromJid = resolveBareJid(currentUser?.userJID)
            ?: resolveBareJid(currentUser?.xmppUsername)
            ?: resolveBareJid(username)
            ?: ""
        
        val presence = """<presence from="$fromJid" to="$fixedRoomJid/$localPart" id="presenceInRoom"><x xmlns="http://jabber.org/protocol/muc"/></presence>"""
        sendRaw(presence)
        return true
    }
    
    /**
     * Get rooms
     * Matches web: getRooms.xmpp.ts
     */
    suspend fun getRooms(): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot get rooms: not authenticated")
            return false
        }
        
        val currentUser = com.ethora.chat.core.store.UserStore.currentUser.value
        val fromJid = resolveBareJid(currentUser?.userJID)
            ?: resolveBareJid(currentUser?.xmppUsername)
            ?: resolveBareJid(username)
            ?: ""
        
        val iq = """<iq type="get" from="$fromJid" id="getUserRooms"><query xmlns="ns:getrooms"/></iq>"""
        sendRaw(iq)
        Log.d(TAG, "📤 Sent getRooms IQ")
        return true
    }
    
    /**
     * Get last message archive
     * Matches web: getLastMessageArchive.xmpp.ts
     */
    suspend fun getLastMessageArchive(roomJid: String): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot get last message archive: not authenticated")
            return false
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@$conference"
        }
        
        val iq = """<iq type="set" to="$fixedRoomJid" id="GetArchive"><query xmlns="urn:xmpp:mam:2"><set xmlns="http://jabber.org/protocol/rsm"><max>1</max><before></before></set></query></iq>"""
        sendRaw(iq)
        Log.d(TAG, "📤 Sent getLastMessageArchive IQ for $fixedRoomJid")
        return true
    }
    
    /**
     * Send ping
     * Matches web: sendPing.xmpp.ts
     */
    suspend fun sendPing(customId: String? = null): String? {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send ping: not authenticated")
            return null
        }
        
        val pingId = customId ?: "ping-${System.currentTimeMillis()}-${(0..9999).random()}"
        val pingStanza = """<iq type="get" to="$host" id="$pingId"><ping xmlns="urn:xmpp:ping"/></iq>"""
        sendRaw(pingStanza)
        Log.d(TAG, "🏓 Sent ping (ID: $pingId)")
        return pingId
    }
    
    /**
     * Disconnect
     */
    /**
     * Subscribe to room messages via MUC-SUB (urn:xmpp:mucsub:0).
     * Matches RN: subscribeToRoomMessages.xmpp.ts
     */
    suspend fun subscribeToRoomMessages(roomJid: String): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot subscribe to room: not authenticated")
            return false
        }

        val fixedRoomJid = if (roomJid.contains("@")) roomJid
            else "$roomJid@$conference"

        val nick = username.split("@").firstOrNull() ?: username
        val id = "newSubscription:${System.currentTimeMillis()}"

        val stanza = """<iq to="$fixedRoomJid" type="set" id="$id"><subscribe xmlns="urn:xmpp:mucsub:0" nick="$nick"><event node="urn:xmpp:mucsub:nodes:messages"/></subscribe></iq>"""

        return suspendCancellableCoroutine { cont ->
            val collector: (XMPPStanza) -> Unit = { stanzaResp ->
                if (stanzaResp.id == id) {
                    if (stanzaResp.type == "result") {
                        cont.resume(true) {}
                    } else {
                        cont.resume(false) {}
                    }
                }
            }

            registerMAMCollector(id, collector)

            scope.launch {
                delay(5000)
                if (cont.isActive) {
                    unregisterMAMCollector(id)
                    cont.resume(false) {}
                }
            }

            sendRaw(stanza)
            Log.d(TAG, "📋 Sent MUC-SUB subscribe for $fixedRoomJid (id=$id)")
        }.also {
            unregisterMAMCollector(id)
        }
    }

    fun disconnect() {
        Log.d(TAG, "🔌 Disconnecting WebSocket...")
        disconnectRequested = true
        webSocket?.close(1000, "Normal closure")
        isConnected = false
        isAuthenticated = false
        authState = AuthState.NOT_STARTED
    }
    
    fun isFullyConnected(): Boolean {
        return isConnected && isAuthenticated
    }
}
