package com.ethora.chat.ui.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ethora.chat.core.call.CallPush
import com.ethora.chat.core.call.CallPushText
import com.ethora.chat.core.call.CallService
import com.ethora.chat.core.call.CallStore
import com.ethora.chat.core.networking.CallsAPIHelper

/**
 * The incoming-call notification for a call push that arrives while the app is not in front
 * (or not running): ring tone, full-screen intent, and "Answer" / "Decline" actions.
 *
 * The host's messaging service calls [show] with the push `data`. "Answer" opens the app and
 * hands the payload back through [handleLaunchIntent]; the call screen then rings and accepts
 * (see [CallOverlayHost]). "Decline" reports the decline to the server without opening the app.
 */
object IncomingCallNotifier {
    const val EXTRA_ANSWER = "ethora_call_answer"
    const val EXTRA_DATA = "ethora_call_data"
    private const val CHANNEL_ID = "ethora_incoming_call"
    private const val NOTIFICATION_ID = 7422
    private const val RING_TIMEOUT_MS = 45_000L

    @Volatile
    private var pendingAnswer: Map<String, String>? = null

    /** Shows the notification. Returns false when the payload is not a call push. */
    fun show(context: Context, data: Map<String, String>): Boolean {
        if (!CallPush.isCallPush(data)) return false
        val app = context.applicationContext
        ensureChannel(app)
        val parsed = CallPush.parse(data)
        val payload = HashMap(data)

        val answer = (app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ANSWER, true)
            putExtra(EXTRA_DATA, payload)
        }
        val answerIntent = PendingIntent.getActivity(
            app, 10, answer, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = Intent(app, IncomingCallActionReceiver::class.java).apply {
            action = IncomingCallActionReceiver.ACTION_DECLINE
            putExtra(EXTRA_DATA, payload)
        }
        val declineIntent = PendingIntent.getBroadcast(
            app, 11, decline, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(CallPushText.title(parsed))
            .setContentText(CallPushText.body(parsed))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(answerIntent)
            .setFullScreenIntent(answerIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
            .addAction(android.R.drawable.sym_action_call, "Answer", answerIntent)
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted.
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    /**
     * Call from the activity's `onCreate` / `onNewIntent`. When the intent is a notification
     * "Answer", remembers the payload for the call screen to ring and accept, and returns true.
     */
    fun handleLaunchIntent(context: Context, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) != true) return false
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val data = intent.getSerializableExtra(EXTRA_DATA) as? Map<String, String> ?: return false
        intent.removeExtra(EXTRA_ANSWER)
        pendingAnswer = data
        cancel(context)
        return true
    }

    /** The payload of an "Answer" tap that the call screen has not consumed yet. */
    fun takePendingAnswer(): Map<String, String>? {
        val data = pendingAnswer
        pendingAnswer = null
        return data
    }

    internal fun declineFromPayload(data: Map<String, String>) {
        val parsed = CallPush.parse(data)
        val ringing = CallStore.snapshot()
        if (ringing.callId != null && ringing.callId == parsed.callId) {
            CallService.declineIncoming(null)
        } else {
            // The app was not running: tell the server so the caller is not left ringing.
            CallsAPIHelper.reportCallState(CallsAPIHelper.CallStateReport.DECLINE, parsed.callId)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when somebody calls you"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }
}

/** "Decline" tap on the incoming-call notification. */
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val data = intent.getSerializableExtra(IncomingCallNotifier.EXTRA_DATA) as? Map<String, String>
        IncomingCallNotifier.cancel(context)
        if (data != null) IncomingCallNotifier.declineFromPayload(data)
    }

    companion object {
        const val ACTION_DECLINE = "com.ethora.chat.call.DECLINE"
    }
}
