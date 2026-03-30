package com.ethora.samplechatapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethora.chat.Chat
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatHeaderSettingsConfig
import com.ethora.chat.core.config.JWTLoginConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.store.ChatStore
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.ethora.chat.core.store.RoomStore
import androidx.core.content.ContextCompat
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.push.PushNotificationManager
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest
class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS permission granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initChatStores()
        PushNotificationManager.initialize(this)
        logSigningCertificateSha1()
        checkFirebaseInit()
        logGooglePlayServicesStatus()
        logFirebaseInstallationId()
        requestNotificationPermission()
        scheduleFcmTokenFetchOnce()
        handleNotificationIntent(intent)

        setContent {
            SampleChatApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun initChatStores() {
        val context = applicationContext
        val persistenceManager = ChatPersistenceManager(context)
        val chatDatabase = ChatDatabase.getDatabase(context)
        val messageCache = MessageCache(chatDatabase)
        RoomStore.initialize(persistenceManager)
        UserStore.initialize(persistenceManager)
        MessageStore.initialize(messageCache)
        ScrollPositionStore.initialize(context)
        MessageLoader.initialize(LocalStorage(context))
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val jid = intent?.getStringExtra("notification_jid")
        if (jid != null) {
            Log.d(TAG, "Opened from push notification, jid=$jid")
            PushNotificationManager.setPendingNotificationJid(jid)
            intent.removeExtra("notification_jid")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkFirebaseInit() {
        try {
            val app = FirebaseApp.getInstance()
            Log.d(TAG, "Firebase OK: project=${app.options.projectId}, appId=${app.options.applicationId}")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase NOT initialized!", e)
        }
    }

    private fun logGooglePlayServicesStatus() {
        val api = GoogleApiAvailability.getInstance()
        val result = api.isGooglePlayServicesAvailable(this)
        if (result == ConnectionResult.SUCCESS) {
            Log.d(TAG, "Google Play services: OK")
        } else {
            Log.e(TAG, "Google Play services: code=$result ${api.getErrorString(result)}")
        }
    }

    private fun logFirebaseInstallationId() {
        FirebaseInstallations.getInstance().id
            .addOnSuccessListener { fid -> Log.d(TAG, "Firebase Installation ID OK: ${fid.take(12)}...") }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase Installation ID FAILED (same layer as FCM token)", e)
            }
    }

    private fun logSigningCertificateSha1() {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val si = info.signingInfo
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                info.signatures!!
            }
            val md = MessageDigest.getInstance("SHA1")
            val sha1 = md.digest(signatures[0].toByteArray())
                .joinToString(":") { b -> "%02X".format(b) }
            Log.w(TAG, "APK signing SHA-1 (verify in Firebase -> com.ethora): $sha1")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read APK signing SHA-1", e)
        }
    }

    private fun scheduleFcmTokenFetchOnce() {
        if (EthoraApplication.fcmRegistrationScheduled) return
        synchronized(EthoraApplication::class.java) {
            if (EthoraApplication.fcmRegistrationScheduled) return
            EthoraApplication.fcmRegistrationScheduled = true
        }
        fetchFcmTokenWithRetry(attempt = 1)
    }

    private fun fetchFcmTokenWithRetry(attempt: Int) {
        val maxAttempts = 6
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    Log.d(TAG, "FCM token OK (attempt $attempt): ${token.take(20)}...")
                    PushNotificationManager.setFcmToken(token)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "FCM attempt $attempt failed: ${e.message}")
                    if (attempt < maxAttempts) {
                        val delayMs = when (attempt) {
                            1 -> 2_000L
                            2 -> 5_000L
                            else -> 10_000L
                        }
                        mainHandler.postDelayed({ fetchFcmTokenWithRetry(attempt + 1) }, delayMs)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "FirebaseMessaging.getInstance() crashed", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleChatApp() {
    val missingConfigFields = remember {
        collectMissingConfigFields()
    }

    var selectedTab by remember { mutableStateOf(0) }
    val rooms by RoomStore.rooms.collectAsState()
    val totalUnread = remember(rooms) { rooms.sumOf { it.unreadMessages } }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (missingConfigFields.isNotEmpty()) {
                SetupRequiredScreen(missingConfigFields)
                return@Surface
            }

            val chatConfig = remember {
                val dnsOverrides = BuildConfig.ETHORA_DNS_FALLBACK_OVERRIDES
                    .split(",")
                    .mapNotNull { part ->
                        val kv = part.split("=", limit = 2).map { it.trim() }
                        if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
                    }
                    .toMap()
                    .takeIf { it.isNotEmpty() }
                ChatConfig(
                    appId = BuildConfig.ETHORA_APP_ID,
                    baseUrl = BuildConfig.ETHORA_API_BASE_URL,
                    disableRooms = false,
                    defaultLogin = false,
                    customAppToken = null,
                    chatHeaderSettings = ChatHeaderSettingsConfig(),
                    xmppSettings = XMPPSettings(
                        xmppServerUrl = BuildConfig.ETHORA_XMPP_SERVER_URL,
                        host = BuildConfig.ETHORA_XMPP_HOST,
                        conference = BuildConfig.ETHORA_XMPP_CONFERENCE
                    ),
                    dnsFallbackOverrides = dnsOverrides,
                    jwtLogin = BuildConfig.ETHORA_USER_JWT
                        .takeIf { it.isNotBlank() }
                        ?.let { token ->
                            JWTLoginConfig(
                                enabled = true,
                                token = token
                            )
                        }
                )
            }

            LaunchedEffect(chatConfig) {
                ChatStore.setConfig(chatConfig)
                ApiClient.setBaseUrl(
                    chatConfig.baseUrl ?: AppConfig.defaultBaseURL,
                    chatConfig.customAppToken
                )
            }

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                if (totalUnread > 0) {
                                    BadgedBox(badge = { Badge { Text(totalUnread.toString()) } }) {
                                        Icon(Icons.Default.Email, contentDescription = "Chat")
                                    }
                                } else {
                                    Icon(Icons.Default.Email, contentDescription = "Chat")
                                }
                            },
                            label = { Text("Chat") }
                        )
                    }
                }
            ) { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                    if (selectedTab == 0) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Welcome to Home Tab!",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    } else {
                        Chat(
                            config = chatConfig,
                            roomJID = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

private fun collectMissingConfigFields(): List<String> {
    val fields = linkedSetOf<String>()

    if (BuildConfig.ETHORA_APP_ID.startsWith("CHANGE_ME")) {
        fields += "ETHORA_APP_ID"
    }
    if (BuildConfig.ETHORA_API_BASE_URL.startsWith("CHANGE_ME")) {
        fields += "ETHORA_API_BASE_URL"
    }

    if (BuildConfig.ETHORA_XMPP_SERVER_URL.contains("CHANGE_ME")) {
        fields += "ETHORA_XMPP_SERVER_URL"
    }
    if (BuildConfig.ETHORA_XMPP_HOST.contains("CHANGE_ME")) {
        fields += "ETHORA_XMPP_HOST"
    }
    if (BuildConfig.ETHORA_XMPP_CONFERENCE.contains("CHANGE_ME")) {
        fields += "ETHORA_XMPP_CONFERENCE"
    }

    return fields.toList()
}

@Composable
private fun SetupRequiredScreen(missingFields: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Sample app requires Ethora credentials",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Update buildConfigField values in sample-chat-app/build.gradle.kts:"
        )
        missingFields.forEach { field ->
            Text(text = "- $field", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "ETHORA_USER_JWT is optional. Leave it empty if you log in another way.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
