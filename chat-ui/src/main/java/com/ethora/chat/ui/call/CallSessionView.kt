package com.ethora.chat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.GroupCallTiles
import com.ethora.chat.core.call.GroupTileInput
import com.ethora.chat.core.xmpp.XMPPClient
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.EglBase

/**
 * Active LiveKit call screen. The room lives in [LiveKitRuntime] (so minimising or recreating
 * the screen does not drop the call); this composable asks for the devices, starts the session
 * and draws it: a remote video with a small self view for a 1:1 call, a grid of everybody for
 * a group call (active speaker outlined, mic-off badge, at most nine tiles). Port of web
 * `VideoCallSession` and its group content.
 *
 * Camera and microphone access are requested first. A refusal does not fail the call: the user
 * stays connected and can turn devices on later.
 */
@Composable
fun CallSessionView(
    token: String,
    livekitUrl: String,
    kind: CallKind,
    isGroup: Boolean,
    title: String,
    startWithCameraOn: Boolean,
    startWithMicOn: Boolean,
    xmppClient: XMPPClient?,
    onMinimize: () -> Unit,
    onHangup: () -> Unit
) {
    val context = LocalContext.current
    val isVideo = kind == CallKind.VIDEO
    val permissions = rememberCallPermissions(token, isVideo)

    val session = remember(token) {
        LiveKitRuntime.acquire(token) {
            LiveKitCallSession(context, token, livekitUrl, isVideo, isGroup) { xmppClient }
        }
    }
    LaunchedEffect(session, permissions.resolved) {
        if (permissions.resolved) {
            session.start(permissions.mic, permissions.camera, startWithMicOn, startWithCameraOn)
        }
    }

    val snap by session.snapshot.collectAsState()
    val router by CallAudioRoute.router.collectAsState()
    val route = router?.selected?.collectAsState()?.value
    val routes = router?.available?.collectAsState()?.value.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
    ) {
        if (isGroup) {
            GroupGrid(
                session = session,
                title = title,
                snapshot = snap,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 56.dp, bottom = 112.dp)
            )
        } else {
            OneToOne(session = session, title = title, isVideo = isVideo, snapshot = snap)
        }

        MinimizeButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            onClick = onMinimize
        )

        CallControlBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            isVideo = isVideo,
            micOn = snap.micOn,
            cameraOn = snap.cameraOn,
            route = route,
            showRouteButton = routes.size > 1,
            onToggleMic = { session.toggleMic() },
            onToggleCamera = { session.toggleCamera() },
            onFlipCamera = { session.flipCamera() },
            onToggleRoute = { router?.toggle() },
            onHangup = onHangup
        )
    }
}

@Composable
private fun OneToOne(
    session: LiveKitCallSession,
    title: String,
    isVideo: Boolean,
    snapshot: LiveKitCallSnapshot
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isVideo && snapshot.remoteVideo != null) {
            VideoView(
                track = snapshot.remoteVideo,
                eglContext = session.eglBase.eglBaseContext,
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

        if (isVideo && snapshot.localVideo != null && snapshot.cameraOn) {
            VideoView(
                track = snapshot.localVideo,
                eglContext = session.eglBase.eglBaseContext,
                mirror = snapshot.facing == CameraPosition.FRONT,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/** Everybody in the room as a grid of tiles (web `GroupCallContent`). */
@Composable
private fun GroupGrid(
    session: LiveKitCallSession,
    title: String,
    snapshot: LiveKitCallSnapshot,
    modifier: Modifier
) {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val selection = remember(snapshot.participants) { GroupCallTiles.select(snapshot.participants) }
    val columns = GroupCallTiles.columns(selection.tiles.size, landscape)
    val rows = GroupCallTiles.rows(selection.tiles.size, columns)

    Column(modifier = modifier.padding(horizontal = 8.dp)) {
        Text(
            text = title.ifBlank { "Group call" },
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp)
        )
        val count = snapshot.participants.size
        Text(
            text = if (!snapshot.connected) "Connecting" else if (count <= 1) "Waiting for others" else "$count participants",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 56.dp, vertical = 2.dp)
        )
        if (selection.hidden > 0) {
            Text(
                text = "+${selection.hidden} more",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(top = 4.dp, start = 56.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (row in 0 until rows) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (column in 0 until columns) {
                        val tile = selection.tiles.getOrNull(row * columns + column)
                        if (tile == null) {
                            Box(modifier = Modifier.weight(1f))
                        } else {
                            ParticipantTile(
                                tile = tile,
                                video = snapshot.videoById[tile.id],
                                eglContext = session.eglBase.eglBaseContext,
                                mirror = tile.isLocal && snapshot.facing == CameraPosition.FRONT,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One participant: live video or an avatar, the name, a mic-off badge, and an outline while speaking. */
@Composable
private fun ParticipantTile(
    tile: GroupTileInput,
    video: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier
) {
    val outline = if (tile.speaking) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
    } else {
        Modifier.border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827))
            .then(outline)
    ) {
        if (video != null) {
            VideoView(track = video, eglContext = eglContext, mirror = mirror, modifier = Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.align(Alignment.Center)) {
                CallAvatar(name = tile.name, size = 64.dp)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x80000000))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (tile.micMuted) {
                Box(
                    modifier = Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFE53935)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MicOff,
                        contentDescription = "Microphone is off",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Text(
                text = if (tile.isLocal) "${tile.name} (You)" else tile.name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
        if (track != null && view != null) {
            try { track.addRenderer(view) } catch (_: Exception) { /* track already released */ }
        }
        onDispose {
            if (track != null && view != null) {
                try { track.removeRenderer(view) } catch (_: Exception) { /* track already released */ }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer?.release() }
    }
}
