package com.ethora.chat.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ethora.chat.core.call.CallKind
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase

/**
 * Active call screen: joins the LiveKit room with the call token, publishes
 * the local microphone and camera, and shows remote and local video with
 * mute, camera, flip and end controls. Port of web `VideoCallSession`.
 *
 * Camera and microphone access are requested first. A refusal does not fail the
 * call: the user stays connected and can turn devices on later. A failed join
 * (bad token, network) is reported through [onError].
 */
@Composable
fun CallSessionView(
    token: String,
    livekitUrl: String,
    kind: CallKind,
    title: String,
    startWithCameraOn: Boolean,
    startWithMicOn: Boolean,
    onConnected: () -> Unit,
    onError: (String) -> Unit,
    onHangup: () -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val isVideo = kind == CallKind.VIDEO

    // One EGL context per call, shared by the room and both video renderers.
    val eglBase = remember(token) { EglBase.create() }
    val room = remember(token) {
        LiveKit.create(appContext, RoomOptions(), LiveKitOverrides(eglBase = eglBase))
    }

    var micOn by remember(token) { mutableStateOf(startWithMicOn) }
    var cameraOn by remember(token) { mutableStateOf(isVideo && startWithCameraOn) }
    var facing by remember(token) { mutableStateOf(CameraPosition.FRONT) }
    var remoteVideo by remember(token) { mutableStateOf<VideoTrack?>(null) }
    var localVideo by remember(token) { mutableStateOf<VideoTrack?>(null) }
    var permissionsResolved by remember(token) { mutableStateOf(false) }

    fun refreshLocalVideo() {
        localVideo = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    // Ask for the devices this call needs, once per call. The result is not
    // gating: the connect below runs either way.
    val requiredPermissions = remember(isVideo) {
        if (isVideo) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsResolved = true
    }
    LaunchedEffect(token) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsResolved = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Join the room, then turn devices on (best effort).
    LaunchedEffect(token, permissionsResolved) {
        if (!permissionsResolved) return@LaunchedEffect
        try {
            room.connect(livekitUrl, token, ConnectOptions(autoSubscribe = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e.message ?: "Could not join the call")
            return@LaunchedEffect
        }
        try {
            room.localParticipant.setMicrophoneEnabled(micOn)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            micOn = false
        }
        if (isVideo) {
            try {
                room.localParticipant.setCameraEnabled(cameraOn)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cameraOn = false
            }
            refreshLocalVideo()
        }
        onConnected()
    }

    // Room events: remote video, and the call ends when the peer leaves.
    LaunchedEffect(room) {
        room.events.collect { event ->
            when (event) {
                is RoomEvent.TrackSubscribed -> {
                    val track = event.track
                    if (track is VideoTrack) remoteVideo = track
                }
                is RoomEvent.TrackUnsubscribed -> {
                    if (event.track == remoteVideo) remoteVideo = null
                }
                is RoomEvent.ParticipantDisconnected -> {
                    if (room.remoteParticipants.isEmpty()) onHangup()
                }
                is RoomEvent.Disconnected -> {
                    if (event.error != null) onError("Call disconnected") else onHangup()
                }
                else -> Unit
            }
        }
    }

    DisposableEffect(token) {
        onDispose {
            try {
                room.disconnect()
                room.release()
            } catch (_: Exception) {
                // Already torn down.
            }
            eglBase.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
    ) {
        if (isVideo && remoteVideo != null) {
            VideoView(
                track = remoteVideo,
                eglContext = eglBase.eglBaseContext,
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
                    text = if (isVideo) "Waiting for video" else "In audio call",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }
        }

        if (isVideo && localVideo != null && cameraOn) {
            VideoView(
                track = localVideo,
                eglContext = eglBase.eglBaseContext,
                mirror = facing == CameraPosition.FRONT,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }

        CallControlBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            isVideo = isVideo,
            micOn = micOn,
            cameraOn = cameraOn,
            onToggleMic = {
                scope.launch {
                    val next = !micOn
                    try {
                        room.localParticipant.setMicrophoneEnabled(next)
                        micOn = next
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Device unavailable: keep the current state.
                    }
                }
            },
            onToggleCamera = {
                scope.launch {
                    val next = !cameraOn
                    try {
                        room.localParticipant.setCameraEnabled(next)
                        cameraOn = next
                        refreshLocalVideo()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Camera unavailable: keep the current state.
                    }
                }
            },
            onFlipCamera = {
                val next = if (facing == CameraPosition.FRONT) CameraPosition.BACK else CameraPosition.FRONT
                val track = localVideo as? LocalVideoTrack
                if (track != null) {
                    try {
                        // Empty device id: the SDK picks the camera for this position.
                        track.switchCamera("", next)
                        facing = next
                    } catch (_: Exception) {
                        // Switching failed: the current camera keeps running.
                    }
                }
            },
            onHangup = onHangup
        )
    }
}

@Composable
private fun CallControlBar(
    modifier: Modifier,
    isVideo: Boolean,
    micOn: Boolean,
    cameraOn: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onHangup: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlCircle(
            icon = if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
            description = if (micOn) "Mute microphone" else "Unmute microphone",
            background = Color(0x33FFFFFF),
            onClick = onToggleMic
        )
        if (isVideo) {
            ControlCircle(
                icon = if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                description = if (cameraOn) "Turn camera off" else "Turn camera on",
                background = Color(0x33FFFFFF),
                onClick = onToggleCamera
            )
            if (cameraOn) {
                ControlCircle(
                    icon = Icons.Filled.Cameraswitch,
                    description = "Flip camera",
                    background = Color(0x33FFFFFF),
                    onClick = onFlipCamera
                )
            }
        }
        ControlCircle(
            icon = Icons.Filled.CallEnd,
            description = "End call",
            background = Color(0xFFE53935),
            onClick = onHangup
        )
    }
}

@Composable
internal fun ControlCircle(
    icon: ImageVector,
    description: String,
    background: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White)
    }
}

/** Renders one video track. The track is attached on entry and detached on change or exit. */
@Composable
private fun VideoView(
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
        if (track != null && view != null) track.addRenderer(view)
        onDispose {
            if (track != null && view != null) track.removeRenderer(view)
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer?.release() }
    }
}

@Composable
internal fun CallAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontSize = (size.value / 2.4f).sp
        )
    }
}
