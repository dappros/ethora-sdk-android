package com.ethora.chat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.CallPhase
import com.ethora.chat.core.call.CallService
import com.ethora.chat.core.call.CallState
import com.ethora.chat.core.call.CallStore
import com.ethora.chat.core.config.VideoCallsConfig
import com.ethora.chat.core.xmpp.XMPPClient

private val DANGER = Color(0xFFE53935)
private val ACCEPT = Color(0xFF10B981)

/**
 * Call UI: incoming ring, outgoing ring, error card and the active session.
 * Mount once at the root of the chat so a call rings on any screen. Port of
 * web `VideoCallOverlay`. Renders nothing while idle or when calls are off.
 *
 * Minimize-to-corner and the browser-style notification from web are not
 * implemented on Android.
 */
@Composable
fun CallOverlayHost(
    videoCalls: VideoCallsConfig?,
    xmppClient: XMPPClient?
) {
    val call by CallStore.state.collectAsState()
    if (videoCalls?.enabled != true || call.phase == CallPhase.IDLE) return

    val livekitUrl = videoCalls.livekitUrl.trim()
    val showSession = (call.phase == CallPhase.CONNECTING || call.phase == CallPhase.IN_CALL) &&
        call.token != null && livekitUrl.isNotEmpty()

    // Back press = Esc on web: decline a ring, dismiss an error, hang up otherwise.
    val onBack: () -> Unit = {
        when (call.phase) {
            CallPhase.RINGING_INCOMING -> CallService.declineIncoming(xmppClient)
            CallPhase.ERROR -> CallService.dismissError()
            else -> CallService.hangUp(xmppClient)
        }
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (showSession) Color(0xFF0B1220) else Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            when {
                showSession -> CallSessionView(
                    token = call.token.orEmpty(),
                    livekitUrl = livekitUrl,
                    kind = call.kind,
                    title = call.roomName.orEmpty(),
                    startWithCameraOn = videoCalls.startWithCameraOn,
                    startWithMicOn = videoCalls.startWithMicOn,
                    onConnected = { CallService.markConnected() },
                    onError = { CallService.reportError(it) },
                    onHangup = { CallService.hangUp(xmppClient) }
                )
                call.phase == CallPhase.RINGING_INCOMING -> RingCard(
                    title = incomingTitle(call),
                    subtitle = if (call.kind == CallKind.AUDIO) "Audio call" else "Video call",
                    name = call.roomName,
                    kind = call.kind,
                    incoming = true,
                    errorMessage = null,
                    onAccept = { CallService.acceptIncoming() },
                    onDecline = { CallService.declineIncoming(xmppClient) },
                    onCancel = null,
                    onDismiss = null
                )
                call.phase == CallPhase.ERROR -> RingCard(
                    title = "Call failed",
                    subtitle = "Please try again later",
                    name = call.roomName,
                    kind = call.kind,
                    incoming = false,
                    errorMessage = call.error,
                    onAccept = null,
                    onDecline = null,
                    onCancel = null,
                    onDismiss = { CallService.dismissError() }
                )
                call.phase == CallPhase.CONNECTING && livekitUrl.isEmpty() -> RingCard(
                    title = "Call failed",
                    subtitle = "Please try again later",
                    name = call.roomName,
                    kind = call.kind,
                    incoming = false,
                    errorMessage = "Video calls unavailable: missing livekitUrl in videoCalls config",
                    onAccept = null,
                    onDecline = null,
                    onCancel = null,
                    onDismiss = { CallService.dismissError() }
                )
                else -> RingCard(
                    title = if (call.phase == CallPhase.CONNECTING) "Connecting…" else "Calling…",
                    subtitle = if (call.phase == CallPhase.CONNECTING) {
                        "Connecting to room"
                    } else {
                        if (call.kind == CallKind.AUDIO) "Audio call" else "Video call"
                    },
                    name = call.roomName,
                    kind = call.kind,
                    incoming = false,
                    errorMessage = null,
                    onAccept = null,
                    onDecline = null,
                    onCancel = { CallService.hangUp(xmppClient) },
                    onDismiss = null
                )
            }
        }
    }
}

private fun incomingTitle(call: CallState): String =
    "Incoming ${if (call.kind == CallKind.AUDIO) "audio" else "video"} call"

@Composable
private fun RingCard(
    title: String,
    subtitle: String,
    name: String?,
    kind: CallKind,
    incoming: Boolean,
    errorMessage: String?,
    onAccept: (() -> Unit)?,
    onDecline: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onDismiss: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CallAvatar(name = name.orEmpty().ifBlank { if (incoming) "Unknown caller" else "Call" }, size = 120.dp)

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name?.takeIf { it.isNotBlank() } ?: if (incoming) "Unknown caller" else "Call",
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = if (kind == CallKind.AUDIO) Icons.Filled.Call else Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = DANGER,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DANGER.copy(alpha = 0.08f))
                    .padding(12.dp)
            )
        }

        if (incoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                ControlCircle(
                    icon = Icons.Filled.CallEnd,
                    description = "Decline call",
                    background = DANGER,
                    onClick = { onDecline?.invoke() }
                )
                ControlCircle(
                    icon = if (kind == CallKind.AUDIO) Icons.Filled.Call else Icons.Filled.Videocam,
                    description = "Accept call",
                    background = ACCEPT,
                    onClick = { onAccept?.invoke() }
                )
            }
        }
        if (onCancel != null) {
            ControlCircle(
                icon = Icons.Filled.CallEnd,
                description = "Cancel call",
                background = DANGER,
                onClick = onCancel
            )
        }
        if (onDismiss != null) {
            Button(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
