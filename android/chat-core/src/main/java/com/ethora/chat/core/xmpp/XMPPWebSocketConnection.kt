package com.ethora.chat.core.xmpp

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * WebSocket-based XMPP connection implementation
 * Similar to Swift XMPPStream_WebSocket
 */
class XMPPWebSocketConnection(
    private val wsUrl: String,
    private val username: String,
    private val password: String,
    private val host: String,
    private val resource: String = "default"
) {
    private val TAG = "XMPPWebSocket"
    private var webSocket: WebSocket? = null
    private var isConnected: Boolean = false
    private var isAuthenticated: Boolean = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var delegate: XMPPClientDelegate? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var authState: AuthState = AuthState.NOT_STARTED
    
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
                    isConnected = false
                    isAuthenticated = false
                    authState = AuthState.NOT_STARTED
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket failure", t)
                    isConnected = false
                    isAuthenticated = false
                    authState = AuthState.NOT_STARTED
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WebSocket connection", e)
            throw e
        }
    }
    
    /**
     * Send stream open (RFC 7395 format: <open> element for WebSocket)
     */
    private suspend fun sendStreamOpen() {
        // RFC 7395: Use <open> element for WebSocket, not <stream:stream>
        val streamOpen = """<open xmlns='urn:ietf:params:xml:ns:xmpp-framing' to='$host' version='1.0'/>"""
        sendRaw(streamOpen)
        authState = AuthState.STREAM_OPENED
        Log.d(TAG, "📤 Sent stream open (RFC 7395): $streamOpen")
    }
    
    /**
     * Handle incoming XMPP stanza
     */
    private suspend fun handleIncomingStanza(xml: String) {
        try {
            Log.d(TAG, "🔍 Handling stanza in state: $authState")
            Log.d(TAG, "   XML: ${xml.take(500)}")
            
            when (authState) {
                AuthState.STREAM_OPENED -> {
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
            // Check if this is a MAM result message
            if (xml.contains("urn:xmpp:mam:2") && xml.contains("<result")) {
                Log.d(TAG, "📨 Received MAM result message")
                // Extract query ID from result element
                val queryIdMatch = "<result[^>]*id=['\"]([^'\"]+)['\"]".toRegex().find(xml)
                val queryId = queryIdMatch?.groupValues?.get(1)
                
                if (queryId != null && mamCollectors.containsKey(queryId)) {
                    val stanza = XMPPStanza(
                        id = extractAttribute(xml, "id") ?: "",
                        type = "message",
                        from = extractAttribute(xml, "from"),
                        to = extractAttribute(xml, "to"),
                        body = null,
                        xml = xml
                    )
                    mamCollectors[queryId]?.invoke(stanza)
                } else {
                    // Check all collectors for matching room
                    val from = extractAttribute(xml, "from") ?: ""
                    mamCollectors.values.forEach { collector ->
                        val stanza = XMPPStanza(
                            id = extractAttribute(xml, "id") ?: "",
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
            if (xml.contains("<iq") && (xml.contains("type=\"result\"") || xml.contains("type='result'"))) {
                val iqId = extractAttribute(xml, "id") ?: ""
                if (iqId.startsWith("get-history:")) {
                    Log.d(TAG, "✅ Received MAM query completion IQ: $iqId")
                    mamQueryComplete.add(iqId)
                }
            }
            
            // Handle normal stanzas
            val stanza = XMPPStanza(
                id = extractAttribute(xml, "id") ?: "",
                type = extractAttribute(xml, "type") ?: "message",
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
    
    private fun extractAttribute(xml: String, attr: String): String? {
        val regex = "$attr=\"([^\"]+)\"".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }
    
    private fun extractElementText(xml: String, element: String): String? {
        val regex = "<$element[^>]*>([^<]+)</$element>".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }
    
    /**
     * Send raw XML
     */
    fun sendRaw(xml: String) {
        webSocket?.send(xml) ?: run {
            Log.e(TAG, "Cannot send: WebSocket is null")
        }
    }
    
    /**
     * Send message to room
     */
    suspend fun sendMessage(roomJid: String, body: String) {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send message: not authenticated")
            return
        }
        
        val messageId = "msg_${System.currentTimeMillis()}"
        // Escape XML special characters in body
        val escapedBody = body
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val message = """<message to="$roomJid" type="groupchat" id="$messageId"><body>$escapedBody</body></message>"""
        sendRaw(message)
        Log.d(TAG, "📤 Sent message to $roomJid: $body")
    }
    
    /**
     * Send MAM query for message history
     */
    suspend fun sendMAMQuery(roomJid: String, max: Int, before: Long? = null, queryId: String): Boolean {
        if (!isAuthenticated) {
            Log.e(TAG, "Cannot send MAM query: not authenticated")
            return false
        }
        
        // Ensure room JID has @conference domain
        val fixedRoomJid = if (roomJid.contains("@")) {
            roomJid
        } else {
            "$roomJid@conference.xmpp.ethoradev.com"
        }
        
        // For MAM pagination, we can use timestamp in ISO 8601 format or message ID
        // Using timestamp in milliseconds converted to ISO 8601 format
        val beforeElement = if (before != null && before > 0) {
            val isoDate = java.time.Instant.ofEpochMilli(before).toString()
            "<before>$isoDate</before>"
        } else {
            ""
        }
        
        val mamQuery = if (beforeElement.isNotEmpty()) {
            """<iq type='set' to='$fixedRoomJid' id='$queryId'><query xmlns='urn:xmpp:mam:2'><set xmlns='http://jabber.org/protocol/rsm'><max>$max</max>$beforeElement</set></query></iq>"""
        } else {
            """<iq type='set' to='$fixedRoomJid' id='$queryId'><query xmlns='urn:xmpp:mam:2'><set xmlns='http://jabber.org/protocol/rsm'><max>$max</max></set></query></iq>"""
        }
        
        sendRaw(mamQuery)
        Log.d(TAG, "📤 Sent MAM query for $fixedRoomJid (max: $max, before: $before)")
        return true
    }
    
    /**
     * Disconnect
     */
    fun disconnect() {
        Log.d(TAG, "🔌 Disconnecting WebSocket...")
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
        isAuthenticated = false
        authState = AuthState.NOT_STARTED
    }
    
    fun isFullyConnected(): Boolean {
        return isConnected && isAuthenticated
    }
}
