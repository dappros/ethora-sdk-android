package com.ethora.chat.ui.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ethora.chat.core.call.CallService
import com.ethora.chat.core.call.CallStore

/**
 * Keeps the process (and the microphone and camera) alive while a call is on, and shows the
 * ongoing-call notification with a "Hang up" action and a tap to return to the app.
 *
 * Started by [CallOverlayHost] when the media session begins, stopped from the call release
 * hook, so it never outlives the call.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            CallService.hangUp(null)
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        val type = foregroundTypes(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type == 0) {
            // Android 14 refuses a microphone/camera service without the matching permission.
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val since = intent?.getLongExtra(EXTRA_SINCE, 0L) ?: 0L
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, title, video, since), type)
        } catch (e: Exception) {
            // Not allowed to start from here (background start restriction): the call goes on without it.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_HANGUP = "com.ethora.chat.call.HANGUP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_SINCE = "since"
        private const val NOTIFICATION_ID = 7421
        private const val CHANNEL_ID = "ethora_ongoing_call"

        @Volatile
        private var appContext: Context? = null
        private var hookRegistered = false

        /** The service types the granted permissions allow (a type without its permission is refused on Android 14). */
        internal fun foregroundTypes(context: Context): Int {
            var type = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (granted(context, Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (granted(context, Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            return type
        }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /** Starts (or refreshes) the service for the active call. */
        fun start(context: Context, title: String, video: Boolean, since: Long?) {
            val app = context.applicationContext
            appContext = app
            synchronized(this) {
                if (!hookRegistered) {
                    hookRegistered = true
                    CallStore.addReleaseHook { appContext?.let { stop(it) } }
                }
            }
            // On Android 14 a microphone/camera service needs the permission first; without it the call has no service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && foregroundTypes(app) == 0) return
            val intent = Intent(app, CallForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VIDEO, video)
                .putExtra(EXTRA_SINCE, since ?: 0L)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (_: Exception) {
                // Background start restriction: the call goes on without the notification.
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, CallForegroundService::class.java))
        }

        internal fun buildNotification(context: Context, title: String, video: Boolean, since: Long): Notification {
            ensureChannel(context)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
            val content = launch?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val hangup = PendingIntent.getService(
                context, 1,
                Intent(context, CallForegroundService::class.java).setAction(ACTION_HANGUP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(title.ifBlank { if (video) "Video call" else "Audio call" })
                .setContentText("Ongoing call, tap to return")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangup)
            if (since > 0L) builder.setWhen(since).setUsesChronometer(true).setShowWhen(true)
            return builder.build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a call is on"
                    setShowBadge(false)
                }
            )
        }
    }
}
