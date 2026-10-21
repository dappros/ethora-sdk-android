package com.ethora.chat.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ethora.chat.core.call.CallDirection
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.CallState
import com.ethora.chat.core.call.IceServerConfig
import com.ethora.chat.core.call.p2p.P2PCallController
import com.ethora.chat.core.call.p2p.P2PCallDeps
import com.ethora.chat.core.call.p2p.P2PCallEvents
import com.ethora.chat.core.call.p2p.P2PCallParams
import com.ethora.chat.core.call.p2p.P2PPayload
import com.ethora.chat.core.call.p2p.P2PRole
import com.ethora.chat.core.call.p2p.P2PRuntime
import com.ethora.chat.core.call.p2p.P2PSignalName
import com.ethora.chat.core.call.p2p.P2PSnapshot
import com.ethora.chat.core.call.p2p.P2PStatus
import com.ethora.chat.core.networking.CallsAPIHelper
import com.ethora.chat.core.xmpp.XMPPClient
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.VideoTrack

/**
 * Active screen of a peer to peer call (1:1, our own WebRTC connection, no LiveKit). The
 * mirror of [CallSessionView] for `mode=p2p`: it asks for the devices, then runs a
 * [P2PCallController] from [P2PRuntime] and shows remote and local video with mute, camera,
 * flip and end controls. Port of web `P2PCallSession`.
 *
 * A refused camera does not fail the call: it continues without our video. A refused
 * microphone leaves the call muted. The controller outcome is reported through the callbacks
 * (run on the main thread).
 */
@Composable
fun P2PSessionView(
    call: CallState,
    title: String,
    startWithCameraOn: Boolean,
    startWithMicOn: Boolean,
    xmppClient: XMPPClient?,
    onConnected: () -> Unit,
    onRemoteEnd: (reason: String) -> Unit,
    onFailed: (String) -> Unit,
    onMinimize: () -> Unit,
    onHangup: () -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val isVideo = call.kind == CallKind.VIDEO
    val callId = call.callId.orEmpty()
    val role = if (call.direction == CallDirection.INCOMING) P2PRole.CALLEE else P2PRole.CALLER
    val key = "$callId:$role"

    val currentOnConnected by rememberUpdatedState(onConnected)
    val currentOnRemoteEnd by rememberUpdatedState(onRemoteEnd)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val currentClient by rememberUpdatedState(xmppClient)

    val permissions = rememberCallPermissions(key, isVideo)
    var controller by remember(key) { mutableStateOf<P2PCallController?>(null) }

    // The controller outlives a recreated screen (rotation): it is looked up by call.
    LaunchedEffect(key, permissions.resolved) {
        if (!permissions.resolved || callId.isEmpty()) return@LaunchedEffect
        val peer = call.peerXmppUsername.orEmpty()
        val created = P2PRuntime.acquire(key) {
            P2PCallController(
                context = appContext,
                params = P2PCallParams(
                    callId = callId,
                    role = role,
                    kind = call.kind,
                    peerLocal = peer,
                    iceServers = call.iceServers,
                    startWithMicOn = startWithMicOn && permissions.mic,
                    startWithCameraOn = startWithCameraOn && permissions.camera
                ),
                deps = object : P2PCallDeps {
                    override suspend fun fetchIceServers(): List<IceServerConfig>? =
                        CallsAPIHelper.fetchIceServers()

                    override fun sendSignal(signal: P2PSignalName, payload: P2PPayload) {
                        P2PRuntime.sendSignal(currentClient, peer, callId, signal, payload)
                    }

                    override fun log(message: String, error: Throwable?) {
                        Log.d("P2PCall", message, error)
                    }
                },
                events = P2PCallEvents(
                    onConnected = { scope.launch { currentOnConnected() } },
                    onRemoteEnd = { reason -> scope.launch { currentOnRemoteEnd(reason) } },
                    onFailed = { message -> scope.launch { currentOnFailed(message) } }
                )
            )
        }
        controller = created
        created.start()
    }

    val active = controller
    val snapshotFlow = remember(active) { active?.snapshot ?: MutableStateFlow(P2PSnapshot()) }
    val snap by snapshotFlow.collectAsState()
    val eglContext = active?.eglBase?.eglBaseContext

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
    ) {
        if (isVideo && snap.remoteVideo != null && snap.remoteVideoLive && eglContext != null) {
            P2PVideoView(
                track = snap.remoteVideo,
                eglContext = eglContext,
                mirror = false,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CallAvatar(name = title, size = 120.dp)
                Text(
                    text = title.ifBlank { "Call" },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = statusLabel(snap, isVideo, role),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }
        }

        if (isVideo && snap.camEnabled && snap.localVideo != null && eglContext != null) {
            P2PVideoView(
                track = snap.localVideo,
                eglContext = eglContext,
                mirror = snap.frontCamera,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }

        snap.notice?.let { notice ->
            Text(
                text = notice,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        MinimizeButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            onClick = onMinimize
        )

        val router by CallAudioRoute.router.collectAsState()
        val route = router?.selected?.collectAsState()?.value
        val routes = router?.available?.collectAsState()?.value.orEmpty()
        CallControlBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            isVideo = isVideo,
            micOn = snap.micEnabled,
            cameraOn = snap.camEnabled,
            route = route,
            showRouteButton = routes.size > 1,
            onToggleMic = { active?.toggleMic() },
            onToggleCamera = { active?.toggleCamera() },
            onFlipCamera = { active?.switchCamera() },
            onToggleRoute = { router?.toggle() },
            onHangup = onHangup
        )
    }
}

private fun statusLabel(snap: P2PSnapshot, isVideo: Boolean, role: P2PRole): String = when (snap.status) {
    P2PStatus.PREPARING -> "Starting call"
    P2PStatus.WAITING -> if (role == P2PRole.CALLER) "Calling" else "Connecting"
    P2PStatus.CONNECTING -> "Connecting"
    P2PStatus.RECONNECTING -> "Reconnecting"
    P2PStatus.CONNECTED -> if (isVideo) "Waiting for video" else "In audio call"
    P2PStatus.FAILED, P2PStatus.ENDED -> "Call ended"
}

/** Renders one WebRTC video track. The track is attached on entry and detached on change or exit. */
@Composable
private fun P2PVideoView(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier
) {
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                init(eglContext, null)
                setMirror(mirror)
                renderer = this
            }
        },
        update = { view -> view.setMirror(mirror) }
    )

    DisposableEffect(track, renderer) {
        val view = renderer
        if (track != null && view != null) {
            try { track.addSink(view) } catch (_: Exception) { /* track already released */ }
        }
        onDispose {
            if (track != null && view != null) {
                try { track.removeSink(view) } catch (_: Exception) { /* track already released */ }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer?.release() }
    }
}
