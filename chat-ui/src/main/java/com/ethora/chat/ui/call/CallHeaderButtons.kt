package com.ethora.chat.ui.call

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.CallPhase
import com.ethora.chat.core.call.CallService
import com.ethora.chat.core.call.CallStore
import com.ethora.chat.core.config.VideoCallsConfig
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient

/**
 * Video (and optional audio) call buttons for a 1:1 room header. Renders nothing
 * when calls are not allowed in this room. Disabled while another call is active.
 */
@Composable
fun CallHeaderButtons(
    room: Room,
    xmppClient: XMPPClient?,
    videoCalls: VideoCallsConfig?
) {
    if (!CallService.canCall(room, videoCalls)) return
    val call by CallStore.state.collectAsState()
    val busy = call.phase != CallPhase.IDLE

    Row {
        IconButton(
            enabled = !busy,
            onClick = { CallService.placeCall(room, CallKind.VIDEO, xmppClient, videoCalls) },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(imageVector = Icons.Filled.Videocam, contentDescription = "Video call")
        }
        if (CallService.canPlaceAudioCall(room, videoCalls)) {
            IconButton(
                enabled = !busy,
                onClick = { CallService.placeCall(room, CallKind.AUDIO, xmppClient, videoCalls) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(imageVector = Icons.Filled.Call, contentDescription = "Audio call")
            }
        }
    }
}
