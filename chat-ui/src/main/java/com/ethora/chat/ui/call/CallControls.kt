package com.ethora.chat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethora.chat.core.call.AudioRoute

internal fun routeIcon(route: AudioRoute): ImageVector = when (route) {
    AudioRoute.SPEAKER -> Icons.Filled.VolumeUp
    AudioRoute.EARPIECE -> Icons.Filled.PhoneInTalk
    AudioRoute.BLUETOOTH -> Icons.Filled.Bluetooth
    AudioRoute.WIRED -> Icons.Filled.Headset
}

internal fun routeLabel(route: AudioRoute): String = when (route) {
    AudioRoute.SPEAKER -> "Speaker"
    AudioRoute.EARPIECE -> "Phone"
    AudioRoute.BLUETOOTH -> "Bluetooth"
    AudioRoute.WIRED -> "Headphones"
}

/** Mic, camera, flip, audio route and hang up. Shared by the LiveKit and the p2p screens. */
@Composable
internal fun CallControlBar(
    modifier: Modifier,
    isVideo: Boolean,
    micOn: Boolean,
    cameraOn: Boolean,
    route: AudioRoute?,
    showRouteButton: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleRoute: () -> Unit,
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
        if (route != null && showRouteButton) {
            ControlCircle(
                icon = routeIcon(route),
                description = "Audio output: ${routeLabel(route)}",
                background = Color(0x33FFFFFF),
                onClick = onToggleRoute
            )
        }
        ControlCircle(
            icon = Icons.Filled.CallEnd,
            description = "End call",
            background = Color(0xFFE53935),
            onClick = onHangup
        )
    }
}

/** Collapses the call screen to a strip; the call goes on. */
@Composable
internal fun MinimizeButton(modifier: Modifier, onClick: () -> Unit) {
    ControlCircle(
        icon = Icons.Filled.CloseFullscreen,
        description = "Minimise call",
        background = Color(0x33FFFFFF),
        onClick = onClick,
        size = 40,
        modifier = modifier
    )
}

@Composable
internal fun ControlCircle(
    icon: ImageVector,
    description: String,
    background: Color,
    onClick: () -> Unit,
    size: Int = 56,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White)
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
