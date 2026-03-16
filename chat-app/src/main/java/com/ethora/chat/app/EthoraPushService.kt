package com.ethora.chat.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ethora.chat.core.push.PushNotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class EthoraPushService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "EthoraPushService"
        const val CHANNEL_ID = "ethora_chat_messages"
        const val EXTRA_ROOM_JID = "room_jid"
    }

    private fun ensureInitialized() {
        if (PushNotificationManager.fcmToken.value == null) {
            PushNotificationManager.initialize(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔔 New FCM token received: ${token.take(20)}...")
        ensureInitialized()
        PushNotificationManager.setFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "🔔 Push received: data=${message.data}, notification=${message.notification?.title}")
        ensureInitialized()

        val jid = message.data["jid"] ?: message.data["chatJid"]

        if (jid != null) {
            PushNotificationManager.setPendingNotificationJid(jid)
        }

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "New message"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""

        showNotification(title, body, jid)
    }

    private fun showNotification(title: String, body: String, roomJid: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new chat messages"
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            roomJid?.let { putExtra(EXTRA_ROOM_JID, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
