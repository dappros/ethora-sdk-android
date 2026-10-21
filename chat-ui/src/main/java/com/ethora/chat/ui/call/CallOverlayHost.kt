package com.ethora.chat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethora.chat.core.call.CallSignaling
import kotlinx.coroutines.delay
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
import com.ethora.chat.core.call.CallDirection
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.CallPhase
import com.ethora.chat.core.call.CallService
import com.ethora.chat.core.call.CallState
import com.ethora.chat.core.call.CallStore
import com.ethora.chat.core.call.p2p.remoteEndNotice
import com.ethora.chat.core.config.VideoCallsConfig
import com.ethora.chat.core.xmpp.XMPPClient

private val DANGER = Color(0xFFE53935)
private val ACCEPT = Color(0xFF10B981)

/**
 * Call UI: incoming ring, outgoing ring, error card and the active session.
 * Mount once at the root of the chat so a call rings on any screen. Port of
 * web `VideoCallOverlay`. Renders nothing while idle or when calls are off.
 *
 * During the media session the screen can be minimised to a strip (back press or the
 * minimise button) and the call goes on; the audio route, the ongoing-call notification and
 * the session itself are owned by the call, not by this screen.
 */
@Composable
fun CallOverlayHost(
    videoCalls: VideoCallsConfig?,
    xmppClient: XMPPClient?
) {
    val call by CallStore.state.collectAsState()
    val minimized by CallUi.minimized.collectAsState()
    val permissionsEpoch by CallUi.permissionsEpoch.collectAsState()
    val appContext = LocalContext.current.applicationContext

    // "Answer" on the incoming-call notification: ring from the push payload and accept at once.
    LaunchedEffect(videoCalls?.enabled) {
        if (videoCalls?.enabled == true) {
            IncomingCallNotifier.takePendingAnswer()?.let { data ->
                if (CallSignaling.ringFromPush(data)) CallService.acceptIncoming()
            }
        }
    }

    if (videoCalls?.enabled != true || call.phase == CallPhase.IDLE) return

    // The server may publish its own LiveKit URL with the call-token (system account only); else the configured one.
    val livekitUrl = (call.livekitUrl ?: videoCalls.livekitUrl).trim()
    val showSession = !call.isP2p &&
        (call.phase == CallPhase.CONNECTING || call.phase == CallPhase.IN_CALL) &&
        call.token != null && livekitUrl.isNotEmpty()
    // A p2p call has no LiveKit token: its own WebRTC session runs from the moment the
    // server answered the dial (caller) or the callee accepted.
    val showP2p = call.isP2p && !call.callId.isNullOrBlank() && when (call.direction) {
        CallDirection.OUTGOING ->
            call.phase == CallPhase.REQUESTING || call.phase == CallPhase.CONNECTING || call.phase == CallPhase.IN_CALL
        CallDirection.INCOMING -> call.phase == CallPhase.CONNECTING || call.phase == CallPhase.IN_CALL
        null -> false
    }
    val inMedia = showSession || showP2p

    // Audio routing and the ongoing-call notification belong to the media session. Both are
    // released by the call itself (see CallStore.addReleaseHook), not by this screen.
    LaunchedEffect(inMedia) {
        if (inMedia) CallAudioRoute.start(appContext, call.kind == CallKind.VIDEO)
    }
    LaunchedEffect(inMedia, permissionsEpoch, call.connectedAt) {
        if (inMedia) {
            CallForegroundService.start(appContext, call.roomName.orEmpty(), call.kind == CallKind.VIDEO, call.connectedAt)
        }
    }

    // Back press = Esc on web: decline a ring, dismiss an error, minimise a live call.
    val onBack: () -> Unit = {
        when {
            call.phase == CallPhase.RINGING_INCOMING -> CallService.declineIncoming(xmppClient)
            call.phase == CallPhase.ERROR -> CallService.dismissError()
            inMedia -> CallUi.setMinimized(true)
            else -> CallService.hangUp(xmppClient)
        }
    }

    if (inMedia && minimized) {
        CallStrip(
            call = call,
            onExpand = { CallUi.setMinimized(false) },
            onHangup = { CallService.hangUp(xmppClient) }
        )
    }

    // The session screens stay composed only while expanded; the call lives in its runtime.
    if (inMedia && minimized) return

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
                .background(if (inMedia) Color(0xFF0B1220) else Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            when {
                showSession -> CallSessionView(
                    token = call.token.orEmpty(),
                    livekitUrl = livekitUrl,
                    kind = call.kind,
                    isGroup = call.isGroup,
                    title = call.roomName.orEmpty(),
                    startWithCameraOn = videoCalls.startWithCameraOn,
                    startWithMicOn = videoCalls.startWithMicOn,
                    xmppClient = xmppClient,
                    onMinimize = { CallUi.setMinimized(true) },
                    onHangup = { CallService.hangUp(xmppClient) }
                )
                showP2p -> P2PSessionView(
                    call = call,
                    title = call.roomName.orEmpty(),
                    startWithCameraOn = videoCalls.startWithCameraOn,
                    startWithMicOn = videoCalls.startWithMicOn,
                    xmppClient = xmppClient,
                    onConnected = { CallService.markConnected() },
                    onRemoteEnd = { reason ->
                        // A failure on the peer's side is shown; a normal hang up just closes the call.
                        val notice = remoteEndNotice(reason)
                        if (notice != null) CallService.reportError(notice) else CallService.hangUp(xmppClient)
                    },
                    onFailed = { CallService.reportError(it) },
                    onMinimize = { CallUi.setMinimized(true) },
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

/** The minimised call: a strip at the top with the name, the running time, expand and hang up. */
@Composable
private fun CallStrip(call: CallState, onExpand: () -> Unit, onHangup: () -> Unit) {
    val elapsed by produceState(0L, call.connectedAt) {
        while (true) {
            val since = call.connectedAt
            value = if (since == null) 0L else ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0L)
            delay(1_000)
        }
    }
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false)
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0B1220))
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (call.kind == CallKind.AUDIO) Icons.Filled.Call else Icons.Filled.Videocam,
                contentDescription = null,
                tint = ACCEPT
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.roomName.orEmpty().ifBlank { "Call" },
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (call.connectedAt == null) "Connecting" else formatCallDuration(elapsed),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
            ControlCircle(
                icon = Icons.Filled.OpenInFull,
                description = "Return to call",
                background = Color(0x33FFFFFF),
                onClick = onExpand,
                size = 36
            )
            ControlCircle(
                icon = Icons.Filled.CallEnd,
                description = "End call",
                background = DANGER,
                onClick = onHangup,
                size = 36
            )
        }
    }
}

/** `m:ss`, or `h:mm:ss` from an hour on. */
internal fun formatCallDuration(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
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
