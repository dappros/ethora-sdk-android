package com.ethora.chat.core.xmpp

import android.util.Log
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.RoomStore
import kotlinx.coroutines.*
import org.jivesoftware.smack.*
import org.jivesoftware.smack.chat2.Chat
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.ping.PingManager
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * XMPP Client implementation using Smack library
 */
class XMPPClient(
    private val username: String,
    private val password: String,
    private val settings: XMPPSettings? = null
) {
    private val TAG = "XMPPClient"

    private var delegate: XMPPClientDelegate? = null
    private var connection: XMPPTCPConnection? = null
    private var webSocketConnection: XMPPWebSocketConnection? = null
    private var chatManager: ChatManager? = null
    private var mucManager: MultiUserChatManager? = null

    private val devServer: String = settings?.devServer ?: "wss://xmpp.ethoradev.com:5443/ws"
    private val host: String = settings?.host ?: "xmpp.ethoradev.com"
    private val conference: String = settings?.conference ?: "conference.xmpp.ethoradev.com"
    
    // Use WebSocket by default if URL starts with ws:// or wss://
    private val useWebSocket: Boolean = devServer.startsWith("ws://") || devServer.startsWith("wss://")

    private var status: ConnectionStatus = ConnectionStatus.OFFLINE
    private var presencesReady: Boolean = false

    // Reconnection
    private var reconnectAttempts: Int = 0
    private val maxReconnectAttempts: Int = 5
    private val reconnectDelay: Long = 2000L
    private var reconnecting: Boolean = false
    private var reconnectJob: Job? = null

    // Ping/Pong
    private var pingJob: Job? = null
    private val pingIntervalMs: Long = 60000L
    private var pingInFlight: Boolean = false

    // Message Queue
    private val messageQueue = mutableListOf<suspend () -> Boolean>()
    private val inFlightIds = CopyOnWriteArraySet<String>()
    private var processingQueue: Boolean = false

    // Track rooms that have received presence responses
    private val roomsWithPresenceResponse = CopyOnWriteArraySet<String>()

    // Active MUCs
    private val activeMUCs = ConcurrentHashMap<String, MultiUserChat>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Set delegate
     */
    fun setDelegate(delegate: XMPPClientDelegate?) {
        this.delegate = delegate
    }

    /**
     * Check if client is online
     */
    fun checkOnline(): Boolean {
        return status == ConnectionStatus.ONLINE && connection?.isConnected == true
    }

    /**
     * Check if client is fully connected and ready to send messages
     */
    fun isFullyConnected(): Boolean {
        val isOnline = status == ConnectionStatus.ONLINE
        val isWsConnected = if (useWebSocket) webSocketConnection?.isFullyConnected() == true else false
        val isTcpConnected = if (!useWebSocket) connection?.isConnected == true else false
        
        return if (useWebSocket) {
            isOnline && isWsConnected
        } else {
            isOnline && isTcpConnected
        }
    }

    /**
     * Check if client is currently connecting
     */
    fun checkConnecting(): Boolean {
        return status == ConnectionStatus.CONNECTING
    }

    /**
     * Check if a room has already received a presence response
     */
    fun hasPresenceResponseForRoom(roomJID: String): Boolean {
        val bareRoomJID = roomJID.split("/").firstOrNull() ?: roomJID
        return roomsWithPresenceResponse.contains(bareRoomJID)
    }

    /**
     * Mark a room as having received a presence response
     */
    fun markPresenceResponseReceived(roomJID: String) {
        val bareRoomJID = roomJID.split("/").firstOrNull() ?: roomJID
        roomsWithPresenceResponse.add(bareRoomJID)
    }

    /**
     * Clear presence response tracking
     */
    fun clearPresenceResponseTracking() {
        roomsWithPresenceResponse.clear()
    }

    /**
     * Initialize and connect XMPP client
     * Note: This is a simplified implementation. For WebSocket support, 
     * you may need to use Smack's WebSocket extension or a different library.
     */
    suspend fun initializeClient() {
        if (status == ConnectionStatus.ONLINE || status == ConnectionStatus.CONNECTING) {
            Log.w(TAG, "Already connected or connecting, skipping initializeClient")
            return
        }

        Log.d(TAG, "Starting XMPP client initialization...")
        Log.d(TAG, "Connection details:")
        Log.d(TAG, "  - WebSocket URL: $devServer")
        Log.d(TAG, "  - Host: $host")
        Log.d(TAG, "  - Conference: $conference")
        Log.d(TAG, "  - Username: $username")
        
        setStatus(ConnectionStatus.CONNECTING)

        try {
            // Disconnect existing connections if any
            connection?.disconnect()
            webSocketConnection?.disconnect()
            Log.d(TAG, "Disconnected any existing connections")

            if (useWebSocket) {
                // Use WebSocket connection
                Log.d(TAG, "Using WebSocket connection")
                webSocketConnection = XMPPWebSocketConnection(
                    wsUrl = devServer,
                    username = username,
                    password = password,
                    host = host,
                    resource = "default"
                )
                webSocketConnection?.setClientWrapper(this@XMPPClient)
                webSocketConnection?.setDelegate(object : XMPPClientDelegate {
                    override fun onXMPPClientConnected(client: XMPPClient) {
                        setStatus(ConnectionStatus.ONLINE)
                        scope.launch {
                            try {
                                sendAllPresencesAndMarkReady()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending presences on connect", e)
                                presencesReady = true
                            }
                        }
                        delegate?.onXMPPClientConnected(this@XMPPClient)
                    }
                    
                    override fun onXMPPClientDisconnected(client: XMPPClient) {
                        setStatus(ConnectionStatus.OFFLINE)
                        presencesReady = false
                        delegate?.onXMPPClientDisconnected(this@XMPPClient)
                    }
                    
                    override fun onMessageReceived(client: XMPPClient, message: Message) {
                        delegate?.onMessageReceived(this@XMPPClient, message)
                    }
                    
                    override fun onStanzaReceived(client: XMPPClient, stanza: XMPPStanza) {
                        delegate?.onStanzaReceived(this@XMPPClient, stanza)
                    }
                    
                    override fun onStatusChanged(client: XMPPClient, status: ConnectionStatus) {
                        setStatus(status)
                        delegate?.onStatusChanged(this@XMPPClient, status)
                    }
                    
                    override fun onComposingReceived(client: XMPPClient, roomJid: String, isComposing: Boolean, composingList: List<String>) {
                        delegate?.onComposingReceived(this@XMPPClient, roomJid, isComposing, composingList)
                    }
                    
                    override fun onMessageEdited(client: XMPPClient, roomJid: String, messageId: String, newText: String) {
                        delegate?.onMessageEdited(this@XMPPClient, roomJid, messageId, newText)
                    }
                    
                    override fun onReactionReceived(client: XMPPClient, roomJid: String, messageId: String, from: String, reactions: List<String>, data: Map<String, String>) {
                        delegate?.onReactionReceived(this@XMPPClient, roomJid, messageId, from, reactions, data)
                    }
                })
                
                Log.d(TAG, "Attempting WebSocket connection...")
                webSocketConnection?.connect()
                
            } else {
                // Use TCP connection (fallback)
                Log.d(TAG, "Using TCP connection (fallback)")
                Log.d(TAG, "Attempting TCP connection to XMPP server...")
                
                // Parse URL to get host and port
                val url = devServer.replace("wss://", "").replace("ws://", "")
                val parts = url.split(":")
                val serverHost = parts[0]
                val serverPort = parts.getOrNull(1)?.toIntOrNull() ?: 5222

                Log.d(TAG, "Creating XMPP connection configuration...")
                Log.d(TAG, "  - Server: $serverHost:$serverPort")
                Log.d(TAG, "  - Domain: $host")
                
                val config = XMPPTCPConnectionConfiguration.builder()
                    .setHost(serverHost)
                    .setPort(serverPort)
                    .setXmppDomain(host)
                    .setUsernameAndPassword(username, password)
                    .setResource(Resourcepart.from("default"))
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
                    .build()

                connection = XMPPTCPConnection(config)
                connection?.addConnectionListener(createConnectionListener())
                Log.d(TAG, "Connection configuration created")

                // Connect
                Log.d(TAG, "Attempting to connect...")
                withContext(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "Calling connection.connect()...")
                        connection?.connect()
                        Log.d(TAG, "TCP connection established!")
                        
                        Log.d(TAG, "Attempting to login...")
                        connection?.login()
                        Log.d(TAG, "Login successful!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Connection error during connect/login: ${e.message}", e)
                        throw e
                    }
                }

                // Initialize managers
                Log.d(TAG, "🔧 Initializing managers...")
                chatManager = ChatManager.getInstanceFor(connection)
                mucManager = MultiUserChatManager.getInstanceFor(connection)
                Log.d(TAG, "Managers initialized")

                // Set up chat listener
                Log.d(TAG, "Setting up message listeners...")
                chatManager?.addIncomingListener { from, message, _ ->
                    Log.d(TAG, "Incoming message from: $from")
                    handleIncomingMessage(message, from)
                }

                // Set up presence listener
                setupPresenceListener()
                Log.d(TAG, "Listeners set up")

                // Start ping
                Log.d(TAG, "Starting ping manager...")
                val pingManager = PingManager.getInstanceFor(connection)
                if (pingManager != null) {
                    startPing()
                    Log.d(TAG, "Ping manager started")
                } else {
                    Log.w(TAG, "Ping manager not available")
                }

                setStatus(ConnectionStatus.ONLINE)
                scope.launch {
                    try {
                        sendAllPresencesAndMarkReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending presences on connect", e)
                        presencesReady = true
                    }
                }
                delegate?.onXMPPClientConnected(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect XMPP client", e)
            Log.e(TAG, "  Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "  Error message: ${e.message}")
            e.printStackTrace()
            setStatus(ConnectionStatus.ERROR)
            // Don't auto-reconnect for testing - let user see the error
            delegate?.onStatusChanged(this, ConnectionStatus.ERROR)
        }
    }

    /**
     * Disconnect XMPP client
     */
    fun disconnect() {
        scope.launch {
            try {
                setStatus(ConnectionStatus.DISCONNECTING)
                stopPing()
                if (useWebSocket) {
                    webSocketConnection?.disconnect()
                } else {
                    connection?.disconnect()
                }
                setStatus(ConnectionStatus.OFFLINE)
                presencesReady = false
                clearPresenceResponseTracking()
                activeMUCs.clear()
                delegate?.onXMPPClientDisconnected(this@XMPPClient)
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting", e)
            }
        }
    }

    /**
     * Send a message to a room
     * Matches web: sendMessage
     * Returns the message ID that was sent, or null if failed
     * Ensures presence is sent before sending message (XMPP requirement)
     */
    suspend fun sendMessage(
        roomJID: String, 
        messageBody: String,
        firstName: String? = null,
        lastName: String? = null,
        photo: String? = null,
        walletAddress: String? = null,
        isReply: Boolean = false,
        showInChannel: Boolean = false,
        mainMessage: String? = null,
        customId: String? = null
    ): String? {
        return try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot send message: not fully connected")
                return null
            }
            
            if (!hasPresenceResponseForRoom(roomJID)) {
                sendPresenceInRoom(roomJID)
                kotlinx.coroutines.delay(200)
            }
            
            if (useWebSocket && webSocketConnection != null) {
                val wsConnection = webSocketConnection
                if (wsConnection != null) {
                    return wsConnection.sendMessage(
                        roomJID, 
                        messageBody,
                        firstName,
                        lastName,
                        photo,
                        walletAddress,
                        isReply,
                        showInChannel,
                        mainMessage,
                        customId
                    )
                }
            } else if (connection != null) {
                val muc = getOrCreateMUC(roomJID)
                val messageId = customId ?: "msg_${System.currentTimeMillis()}"
                muc.sendMessage(messageBody)
                return messageId
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            null
        }
    }
    
    /**
     * Send media message
     */
    suspend fun sendMediaMessage(roomJID: String, mediaData: Map<String, Any>, messageId: String): Boolean {
        return try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot send media message: not fully connected")
                return false
            }
            
            if (!hasPresenceResponseForRoom(roomJID)) {
                sendPresenceInRoom(roomJID)
                kotlinx.coroutines.delay(200)
            }
            
            if (useWebSocket && webSocketConnection != null) {
                val wsConnection = webSocketConnection
                if (wsConnection != null) {
                    return wsConnection.sendMediaMessage(roomJID, mediaData, messageId)
                }
            } else {
                Log.e(TAG, "Media messages not supported for TCP connection")
                return false
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media message", e)
            false
        }
    }

    /**
     * Get message history using MAM (Message Archive Management)
     * Matches Swift: SendGetHistory.swift
     * - When beforeMessageId is null: requests newest messages (RSM <before/>)
     * - When beforeMessageId is provided: requests older messages before that ID
     * Note: Swift uses message ID (Int64) converted from message.id string using Number()
     */
    suspend fun getHistory(roomJID: String, max: Int = 30, beforeMessageId: String? = null): List<Message> {
        return try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot get history: not fully connected")
                return emptyList()
            }
            
            val isInitialLoad = beforeMessageId == null
            
            if (useWebSocket && webSocketConnection != null) {
                val queryId = "get-history:${System.currentTimeMillis()}"
                val messages = mutableListOf<Message>()
                val latch = kotlinx.coroutines.sync.Mutex()
                var queryComplete = false
                
                val collector: (XMPPStanza) -> Unit = { stanza ->
                    scope.launch {
                        latch.lock()
                        try {
                            if (!queryComplete) {
                                val parsedMessages = parseMAMResult(stanza, roomJID)
                                val filteredMessages = parsedMessages.filter { message ->
                                    message.roomJid == roomJID
                                }
                                if (filteredMessages.isNotEmpty()) {
                                    com.ethora.chat.core.store.MessageStore.addMessages(roomJID, filteredMessages)
                                    messages.addAll(filteredMessages)
                                }
                            }
                        } finally {
                            latch.unlock()
                        }
                    }
                }
                
                webSocketConnection?.registerMAMCollector(queryId, collector)
                
                val sent = webSocketConnection?.sendMAMQuery(roomJID, max, beforeMessageId, queryId) ?: false
                if (!sent) {
                    Log.e(TAG, "Failed to send MAM query")
                    webSocketConnection?.unregisterMAMCollector(queryId)
                    return emptyList()
                }
                
                val timeout = 15000L
                val startTime = System.currentTimeMillis()
                
                while (!queryComplete && (System.currentTimeMillis() - startTime) < timeout) {
                    kotlinx.coroutines.delay(100)
                    if (webSocketConnection?.isMAMQueryComplete(queryId) == true) {
                        queryComplete = true
                    }
                    latch.lock()
                    try {
                        if (messages.size >= max && (System.currentTimeMillis() - startTime) > 1000) {
                            // Give it at least 1s even if we have max messages, to ensure <fin> is processed
                            queryComplete = true
                        }
                    } finally {
                        latch.unlock()
                    }
                }
                
                if (!queryComplete) {
                    Log.w(TAG, "MAM query $queryId reached timeout. Collected ${messages.size}/$max messages")
                }
                
                // Unregister collector and clear status
                webSocketConnection?.unregisterMAMCollector(queryId)
                webSocketConnection?.clearMAMQueryStatus(queryId)
                
                latch.lock()
                try {
                    val sortedMessages = if (isInitialLoad) {
                        messages.sortedByDescending { it.timestamp ?: it.date.time }
                    } else {
                        messages.sortedBy { it.timestamp ?: it.date.time }
                    }
                    return sortedMessages
                } finally {
                    latch.unlock()
                }
            } else {
                Log.e(TAG, "WebSocket connection not available for MAM query")
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get history", e)
            emptyList()
        }
    }
    
    /**
     * Helper function to convert message ID string to Long (for pagination)
     * Returns null if message ID is not numeric
     */
    private fun messageIdToLong(messageId: String): Long? {
        return try {
            messageId.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse MAM result message and extract Message objects
     */
    private fun parseMAMResult(stanza: XMPPStanza, roomJID: String): List<Message> {
        val messages = mutableListOf<Message>()
        try {
            val xml = stanza.xml ?: return emptyList()
            
            // Keep MAM parser lightweight: this path can run frequently on large rooms.
            
            // Check if this is a MAM result message
            // Format: <message><result xmlns='urn:xmpp:mam:2'><forwarded><message>...</message></forwarded></result></message>
            if (!xml.contains("urn:xmpp:mam:2") || !xml.contains("<result")) {
                Log.d(TAG, "  Not a MAM result message")
                return emptyList()
            }
            
            // Extract result ID (this is the MAM result ID, not the message ID)
            val resultIdMatch = "<result\\b[^>]*\\bid=['\"]([^'\"]+)['\"]".toRegex().find(xml)
            val resultId = resultIdMatch?.groupValues?.get(1) ?: ""
            
            // Extract inner message from MAM structure
            // Pattern: <result ...><forwarded><message ...>...</message></forwarded></result>
            val forwardedStart = xml.indexOf("<forwarded")
            if (forwardedStart == -1) {
                Log.d(TAG, "  No <forwarded> element found")
                return emptyList()
            }
            
            // Find the end of forwarded element (handle nested XML)
            var forwardedEnd = forwardedStart
            var depth = 0
            var inForwarded = false
            for (i in forwardedStart until xml.length) {
                when {
                    xml.substring(i).startsWith("<forwarded") -> {
                        inForwarded = true
                        depth++
                    }
                    xml.substring(i).startsWith("</forwarded>") -> {
                        depth--
                        if (depth == 0 && inForwarded) {
                            forwardedEnd = i + "</forwarded>".length
                            break
                        }
                    }
                }
            }
            
            if (forwardedEnd == forwardedStart) {
                // Fallback: find first </forwarded>
                forwardedEnd = xml.indexOf("</forwarded>", forwardedStart)
                if (forwardedEnd == -1) {
                    Log.d(TAG, "  Could not find </forwarded> end tag")
                    return emptyList()
                }
                forwardedEnd += "</forwarded>".length
            }
            
            val forwardedXml = xml.substring(forwardedStart, forwardedEnd)
            
            // Extract delay element for timestamp (before message)
            val delayStart = forwardedXml.indexOf("<delay")
            val delayEnd = forwardedXml.indexOf("/>", delayStart)
            val delayXml = if (delayStart != -1 && delayEnd != -1) {
                forwardedXml.substring(delayStart, delayEnd + 2)
            } else ""
            
            val timestampStr = extractAttribute(delayXml, "stamp")
            val timestamp = timestampStr?.let {
                try {
                    // Parse XEP-0082 timestamp format: 2024-10-03T15:30:10.723554Z
                    java.time.Instant.parse(it).toEpochMilli()
                } catch (e: Exception) {
                    Log.w(TAG, "  Failed to parse timestamp: $it", e)
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
            
            // Extract inner message element
            val messageStart = forwardedXml.indexOf("<message")
            if (messageStart == -1) {
                Log.d(TAG, "  No <message> element in forwarded")
                return emptyList()
            }
            
            // Find the end of message element (handle nested XML)
            var messageEnd = messageStart
            var msgDepth = 0
            var inMessage = false
            for (i in messageStart until forwardedXml.length) {
                when {
                    forwardedXml.substring(i).startsWith("<message") && !forwardedXml.substring(i + 1).startsWith("/") -> {
                        inMessage = true
                        msgDepth++
                    }
                    forwardedXml.substring(i).startsWith("</message>") -> {
                        msgDepth--
                        if (msgDepth == 0 && inMessage) {
                            messageEnd = i + "</message>".length
                            break
                        }
                    }
                }
            }
            
            if (messageEnd == messageStart) {
                // Fallback: find first </message>
                messageEnd = forwardedXml.indexOf("</message>", messageStart)
                if (messageEnd == -1) {
                    Log.d(TAG, "  Could not find </message> end tag")
                    return emptyList()
                }
                messageEnd += "</message>".length
            }
            
            val messageXml = forwardedXml.substring(messageStart, messageEnd)
            
            // Parse message attributes
            val from = extractAttribute(messageXml, "from") ?: ""
            val messageId = extractAttribute(messageXml, "id") ?: ""
            val type = extractAttribute(messageXml, "type") ?: "groupchat"
            
            // Extract room JID from 'from' attribute (format: room@conference.domain/user@domain/resource)
            // This prevents messages from going to wrong chats when multiple rooms load history simultaneously
            val actualRoomJid = from.split("/").firstOrNull() ?: roomJID
            
            Log.d(TAG, "  Extracted - ID: $messageId, From: $from, Type: $type, Room: $actualRoomJid")
            
            // Extract body - handle multiline and nested content
            val bodyStart = messageXml.indexOf("<body>")
            val bodyEnd = messageXml.indexOf("</body>", bodyStart)
            val body = if (bodyStart != -1 && bodyEnd != -1) {
                messageXml.substring(bodyStart + "<body>".length, bodyEnd)
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
            } else {
                ""
            }
            
            // Skip if no body (might be presence or other non-chat message)
            if (body.isBlank()) {
                Log.d(TAG, "  Skipping message with no body")
                return emptyList()
            }
            
            // Extract user info from 'from' attribute
            // Format: room@conference.domain/user@domain/resource
            val userJid = from.split("/").getOrNull(1) ?: from
            val username = userJid.split("@").firstOrNull() ?: "unknown"
            
            // Extract user info from <data> element (if present)
            // Format: <data xmlns="..." senderFirstName="..." senderLastName="..." photoURL="..." />
            // Also check in the full forwarded XML, not just message XML
            val dataStartInMessage = messageXml.indexOf("<data")
            val dataEndInMessage = messageXml.indexOf("/>", dataStartInMessage)
            val dataStartInForwarded = forwardedXml.indexOf("<data")
            val dataEndInForwarded = forwardedXml.indexOf("/>", dataStartInForwarded)
            
            val dataXml = when {
                dataStartInMessage != -1 && dataEndInMessage != -1 -> {
                    messageXml.substring(dataStartInMessage, dataEndInMessage + 2)
                }
                dataStartInForwarded != -1 && dataEndInForwarded != -1 -> {
                    forwardedXml.substring(dataStartInForwarded, dataEndInForwarded + 2)
                }
                else -> ""
            }
            
            Log.d(TAG, "  Data XML found: ${dataXml.isNotEmpty()}, length: ${dataXml.length}")
            
            // Extract attributes - try both quoted and unquoted formats
            val senderFirstName = extractAttribute(dataXml, "senderFirstName")
            val senderLastName = extractAttribute(dataXml, "senderLastName")
            val photoURL = extractAttribute(dataXml, "photoURL")
            val fullName = extractAttribute(dataXml, "fullName")
            val senderJID = extractAttribute(dataXml, "senderJID")
            
            val cleanPhotoURL = photoURL?.takeIf { it.isNotBlank() && it != "none" && it.isNotEmpty() }
            
            Log.d(TAG, "  Extracted user data: firstName=$senderFirstName, lastName=$senderLastName, photoURL=${cleanPhotoURL?.take(50)}, fullName=$fullName, senderJID=$senderJID")
            
            // For MAM pagination, prefer archive/result id (server id) like web component.
            // Keep inner stanza message id in xmppId for pending reconciliation and dedupe.
            val archiveMessageId = when {
                resultId.isNotBlank() -> resultId
                messageId.isNotBlank() -> messageId
                else -> timestamp.toString()
            }
            val stanzaMessageId = when {
                messageId.isNotBlank() -> messageId
                resultId.isNotBlank() -> resultId
                else -> archiveMessageId
            }
            
            // Determine user ID - prefer senderJID, then userJid, then username
            val userId = senderJID?.split("@")?.firstOrNull() 
                ?: userJid.split("@").firstOrNull() 
                ?: username
            
            // Create User object with extracted info
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
            
            Log.d(TAG, "  User: ${user.fullName}, profileImage: ${user.profileImage?.take(50)}")
            
            // Check if message is deleted (has <deleted> element)
            val isDeleted = messageXml.contains("<deleted") || messageXml.contains("<deleted>") || 
                           forwardedXml.contains("<deleted") || forwardedXml.contains("<deleted>")
            
            // Create Message object - use actual room JID from stanza
            val message = Message(
                id = archiveMessageId,
                user = user,
                date = java.util.Date(timestamp),
                body = body,
                roomJid = actualRoomJid, // Use room JID extracted from stanza, not parameter
                timestamp = timestamp,
                xmppId = stanzaMessageId,
                xmppFrom = from,
                isDeleted = isDeleted
            )
            
            messages.add(message)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MAM result", e)
            e.printStackTrace()
        }
        
        return messages
    }
    
    private fun extractAttribute(xml: String, attr: String): String? {
        if (xml.isEmpty()) return null
        
        // Try double quotes first (default)
        val doubleQuoteRegex = "$attr=\"([^\"]+)\"".toRegex()
        val doubleQuoteMatch = doubleQuoteRegex.find(xml)
        if (doubleQuoteMatch != null) {
            return doubleQuoteMatch.groupValues.getOrNull(1)
        }
        
        // Try single quotes
        val singleQuoteRegex = "$attr='([^']+)'".toRegex()
        val singleQuoteMatch = singleQuoteRegex.find(xml)
        if (singleQuoteMatch != null) {
            return singleQuoteMatch.groupValues.getOrNull(1)
        }
        
        // Try without quotes (some XML might have unquoted attributes)
        val noQuoteRegex = "$attr=([^\\s>]+)".toRegex()
        val noQuoteMatch = noQuoteRegex.find(xml)
        return noQuoteMatch?.groupValues?.getOrNull(1)
    }
    
    private fun extractElementText(xml: String, element: String): String? {
        val regex = "<$element[^>]*>([^<]+)</$element>".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }

    /**
     * Join a room
     */
    suspend fun joinRoom(roomJID: String, nickname: String? = null): Boolean {
        return try {
            val muc = getOrCreateMUC(roomJID)
            val nick = nickname ?: username.split("@").firstOrNull() ?: "user"
            muc.join(Resourcepart.from(nick))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join room", e)
            false
        }
    }

    /**
     * Leave a room
     */
    suspend fun leaveRoom(roomJID: String): Boolean {
        return try {
            val muc = activeMUCs[roomJID]
            muc?.leave()
            activeMUCs.remove(roomJID)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to leave room", e)
            false
        }
    }

    /**
     * Send general presence (available)
     */
    suspend fun sendGeneralPresence(): Boolean {
        com.ethora.chat.core.store.LogStore.info(TAG, "Executing sendGeneralPresence()")
        return try {
            if (status != ConnectionStatus.ONLINE) {
                com.ethora.chat.core.store.LogStore.error(TAG, "Cannot send general presence: not online")
                Log.e(TAG, "Cannot send general presence: not online")
                return false
            }
            
            if (useWebSocket && webSocketConnection != null) {
                com.ethora.chat.core.store.LogStore.send(TAG, "Sending general presence via WebSocket")
                webSocketConnection?.sendGeneralPresence()
                return true
            } else {
                try {
                    com.ethora.chat.core.store.LogStore.send(TAG, "Sending general presence via TCP")
                    val presence = Presence(Presence.Type.available)
                    connection?.sendStanza(presence)
                    return true
                } catch (e: Exception) {
                    com.ethora.chat.core.store.LogStore.error(TAG, "Failed to send general presence via TCP: ${e.message}")
                    Log.e(TAG, "Failed to send general presence via TCP", e)
                    return false
                }
            }
        } catch (e: Exception) {
            com.ethora.chat.core.store.LogStore.error(TAG, "Failed to send general presence: ${e.message}")
            Log.e(TAG, "Failed to send general presence", e)
            false
        }
    }
    
    /**
     * Send presence to all rooms
     */
    suspend fun sendAllPresencesToRooms(): Boolean {
        return try {
            // Check if client is connected before sending presence
            if (!isFullyConnected()) {
                Log.w(TAG, "Cannot send presence to rooms: not fully connected")
                return false
            }
            
            val rooms = RoomStore.rooms.value
            if (rooms.isEmpty()) {
                return true
            }
            
            rooms.forEach { room ->
                try {
                    sendPresenceInRoom(room.jid)
                    kotlinx.coroutines.delay(50)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send presence to ${room.jid}", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send presence to all rooms", e)
            false
        }
    }
    
    /**
     * Send general presence and then presence to all rooms
     */
    suspend fun sendAllPresencesAndMarkReady() {
        presencesReady = false
        try {
            sendGeneralPresence()
            kotlinx.coroutines.delay(200)
            sendAllPresencesToRooms()
            presencesReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendAllPresencesAndMarkReady", e)
            presencesReady = true
        }
    }
    
    /**
     * Send presence in room
     */
    suspend fun sendPresenceInRoom(roomJID: String): Boolean {
        com.ethora.chat.core.store.LogStore.info(TAG, "Executing sendPresenceInRoom($roomJID)")
        return try {
            if (!isFullyConnected()) {
                com.ethora.chat.core.store.LogStore.error(TAG, "Cannot send presence in room: not fully connected")
                Log.e(TAG, "Cannot send presence: not fully connected")
                return false
            }
            
            if (useWebSocket && webSocketConnection != null) {
                com.ethora.chat.core.store.LogStore.send(TAG, "Sending presence in room: $roomJID")
                webSocketConnection?.sendPresenceInRoom(roomJID)
                return true
            } else {
                com.ethora.chat.core.store.LogStore.error(TAG, "Presence in room not supported for TCP connection")
                Log.e(TAG, "Presence in room not supported for TCP connection")
                return false
            }
        } catch (e: Exception) {
            com.ethora.chat.core.store.LogStore.error(TAG, "Failed to send presence in room: ${e.message}")
            Log.e(TAG, "Failed to send presence", e)
            false
        }
    }
    
    /**
     * Get rooms
     * Matches web: getRoomsStanza
     */
    suspend fun getRooms(): Boolean {
        com.ethora.chat.core.store.LogStore.info(TAG, "Executing getRooms()")
        return try {
            if (!isFullyConnected()) {
                com.ethora.chat.core.store.LogStore.error(TAG, "Cannot get rooms: not fully connected")
                Log.e(TAG, "Cannot get rooms: not fully connected")
                return false
            }
            
            if (useWebSocket && webSocketConnection != null) {
                com.ethora.chat.core.store.LogStore.send(TAG, "Sending getRooms request via WebSocket")
                webSocketConnection?.getRooms()
                return true
            } else {
                com.ethora.chat.core.store.LogStore.error(TAG, "Get rooms not supported for TCP connection")
                Log.e(TAG, "Get rooms not supported for TCP connection")
                return false
            }
        } catch (e: Exception) {
            com.ethora.chat.core.store.LogStore.error(TAG, "Failed to get rooms: ${e.message}")
            Log.e(TAG, "Failed to get rooms", e)
            false
        }
    }
    
    /**
     * Get last message archive
     * Matches web: getLastMessageArchiveStanza
     */
    suspend fun getLastMessageArchive(roomJID: String): Boolean {
        return try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot get last message archive: not fully connected")
                return false
            }
            
            if (useWebSocket && webSocketConnection != null) {
                webSocketConnection?.getLastMessageArchive(roomJID)
                return true
            } else {
                Log.e(TAG, "Get last message archive not supported for TCP connection")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last message archive", e)
            false
        }
    }
    
    /**
     * Send ping
     * Matches web: sendPingStanza
     */
    suspend fun sendPing(customId: String? = null): String? {
        return try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot send ping: not fully connected")
                return null
            }
            
            if (useWebSocket && webSocketConnection != null) {
                return webSocketConnection?.sendPing(customId)
            } else {
                Log.e(TAG, "Ping not supported for TCP connection")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ping", e)
            null
        }
    }
    
    /**
     * Send typing indicator (composing/paused)
     * Matches web: useComposing hook
     */
    suspend fun sendTypingIndicator(roomJID: String, fullName: String, isTyping: Boolean) {
        try {
            if (useWebSocket && webSocketConnection != null) {
                val wsConnection = webSocketConnection
                if (wsConnection != null) {
                    wsConnection.sendTypingIndicator(roomJID, fullName, isTyping)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send typing indicator", e)
        }
    }

    /**
     * Edit message
     * Matches web: editMessageStanza
     */
    suspend fun editMessage(roomJID: String, messageId: String, newText: String) {
        try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot edit message: not fully connected")
                return
            }
            
            if (useWebSocket && webSocketConnection != null) {
                webSocketConnection?.editMessage(roomJID, messageId, newText)
            } else {
                Log.e(TAG, "Edit message not supported for TCP connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to edit message", e)
        }
    }

    /**
     * Delete message
     * Matches web: deleteMessageStanza
     */
    suspend fun deleteMessage(roomJID: String, messageId: String) {
        try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot delete message: not fully connected")
                return
            }
            
            if (useWebSocket && webSocketConnection != null) {
                webSocketConnection?.deleteMessage(roomJID, messageId)
            } else {
                Log.e(TAG, "Delete message not supported for TCP connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete message", e)
        }
    }

    /**
     * Send typing request (Chat State Notification)
     * Matches web: sendTypingRequestStanza
     */
    fun sendTypingRequest(roomJid: String, fullName: String, isTyping: Boolean) {
        try {
            if (useWebSocket && webSocketConnection != null) {
                // Determine whether to send 'composing' (start) or 'paused' (stop)
                // Note: 'active' state is often used to stop typing, but web uses 'paused' or implies stop by absence
                // Web implementation sends <composing/> or <paused/> inside <message>
                
                val type = if (isTyping) "composing" else "paused"
                val id = if (isTyping) "typing-${System.currentTimeMillis()}" else "stop-typing-${System.currentTimeMillis()}"
                
                // Construct XML stanza manually since we need custom chatstates namespace
                // Matches web: sendTypingRequest.xmpp.ts
                val xml = """
                    <message to='$roomJid' id='$id' type='groupchat'>
                        <$type xmlns='http://jabber.org/protocol/chatstates'/>
                        <data fullName='$fullName'/>
                    </message>
                """.trimIndent()
                
                webSocketConnection?.send(xml)
            } else {
                Log.d(TAG, "Typing notifications not supported for TCP connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send typing request", e)
        }
    }

    /**
     * Send message reaction
     * Matches web: sendMessageReactionStanza
     */
    suspend fun sendMessageReaction(roomJID: String, messageId: String, reactions: List<String>) {
        try {
            if (!isFullyConnected()) {
                Log.e(TAG, "Cannot send reaction: not fully connected")
                return
            }
            
            if (useWebSocket && webSocketConnection != null) {
                webSocketConnection?.sendMessageReaction(roomJID, messageId, reactions)
            } else {
                Log.e(TAG, "Reactions not supported for TCP connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reaction", e)
        }
    }

    /**
     * Send presence
     */
    fun sendPresence(presence: Presence) {
        try {
            connection?.sendStanza(presence)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send presence", e)
        }
    }

    /**
     * Get or create MUC
     */
    private suspend fun getOrCreateMUC(roomJID: String): MultiUserChat {
        return activeMUCs.getOrPut(roomJID) {
            val roomJid = try {
                JidCreate.entityBareFrom(roomJID)
            } catch (e: Exception) {
                throw Exception("Invalid room JID: $roomJID", e)
            }
            MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(roomJid)
        }
    }

    /**
     * Create connection listener
     */
    private fun createConnectionListener(): ConnectionListener {
        return object : ConnectionListener {
            override fun connected(connection: XMPPConnection?) {
                Log.d(TAG, "XMPP connected")
            }

            override fun authenticated(connection: XMPPConnection?, resumed: Boolean) {
                Log.d(TAG, "XMPP authenticated")
            }

            override fun connectionClosed() {
                Log.d(TAG, "XMPP connection closed")
                setStatus(ConnectionStatus.OFFLINE)
                presencesReady = false
            }

            override fun connectionClosedOnError(e: Exception?) {
                Log.e(TAG, "XMPP connection closed on error", e)
                setStatus(ConnectionStatus.ERROR)
                handleReconnection()
            }

            // Note: reconnectingIn, reconnectionSuccessful, reconnectionFailed 
            // are deprecated in newer Smack versions, using connectionClosedOnError instead
        }
    }

    /**
     * Setup presence listener
     */
    private fun setupPresenceListener() {
        val roster = Roster.getInstanceFor(connection)
        roster.addRosterListener(object : RosterListener {
            override fun entriesAdded(addresses: Collection<org.jxmpp.jid.Jid>?) {}
            override fun entriesUpdated(addresses: Collection<org.jxmpp.jid.Jid>?) {}
            override fun entriesDeleted(addresses: Collection<org.jxmpp.jid.Jid>?) {}
            override fun presenceChanged(presence: Presence?) {
                presence?.let {
                    val stanza = XMPPStanza(
                        type = "presence",
                        from = it.from?.toString(),
                        to = it.to?.toString(),
                        id = it.stanzaId,
                        body = null,
                        stanza = it
                    )
                    delegate?.onStanzaReceived(this@XMPPClient, stanza)
                }
            }
        })
    }

    /**
     * Handle incoming message
     */
    private fun handleIncomingMessage(message: org.jivesoftware.smack.packet.Message, from: org.jxmpp.jid.Jid) {
        scope.launch {
            try {
                // Parse message and create Message model
                // This is a simplified version - you'll need to parse the full message structure
                val chatMessage = Message(
                    id = message.stanzaId ?: System.currentTimeMillis().toString(),
                    user = com.ethora.chat.core.models.User(
                        id = from.toString(),
                        xmppUsername = from.toString()
                    ),
                    date = java.util.Date(),
                    body = message.body ?: "",
                    roomJid = from.asBareJid().toString()
                )

                delegate?.onMessageReceived(this@XMPPClient, chatMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming message", e)
            }
        }
    }

    /**
     * Start ping
     */
    private fun startPing() {
        stopPing()
        pingJob = scope.launch {
            while (status == ConnectionStatus.ONLINE && connection?.isConnected == true) {
                delay(pingIntervalMs)
                try {
                    if (!pingInFlight && connection != null) {
                        pingInFlight = true
                        val pingManager = PingManager.getInstanceFor(connection)
                        if (pingManager != null) {
                            // Ping the server
                            pingManager.pingMyServer()
                        }
                        pingInFlight = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ping failed", e)
                    pingInFlight = false
                }
            }
        }
    }

    /**
     * Stop ping
     */
    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * Handle reconnection
     */
    private fun handleReconnection() {
        if (reconnecting || reconnectAttempts >= maxReconnectAttempts) {
            return
        }

        reconnecting = true
        reconnectJob = scope.launch {
            delay(reconnectDelay * (reconnectAttempts + 1))
            reconnectAttempts++
            reconnecting = false
            initializeClient()
        }
    }

    /**
     * Set status
     */
    private fun setStatus(newStatus: ConnectionStatus) {
        if (status != newStatus) {
            status = newStatus
            delegate?.onStatusChanged(this, newStatus)
        }
    }

    /**
     * Cleanup
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
