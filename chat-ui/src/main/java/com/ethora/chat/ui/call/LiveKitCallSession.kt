package com.ethora.chat.ui.call

import android.content.Context
import com.ethora.chat.core.call.CallService
import com.ethora.chat.core.call.CallStore
import com.ethora.chat.core.call.GroupTileInput
import com.ethora.chat.core.xmpp.XMPPClient
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase

/** What the call screen draws, rebuilt on every room event. */
data class LiveKitCallSnapshot(
    val connected: Boolean = false,
    val micOn: Boolean = true,
    val cameraOn: Boolean = false,
    val facing: CameraPosition = CameraPosition.FRONT,
    /** Everybody in the room, us included (group grid). */
    val participants: List<GroupTileInput> = emptyList(),
    /** Camera track per participant id (only live, unmuted ones). */
    val videoById: Map<String, VideoTrack> = emptyMap(),
    /** The first remote camera, for the 1:1 layout. */
    val remoteVideo: VideoTrack? = null,
    val localVideo: VideoTrack? = null
)

/**
 * One LiveKit call (1:1 or group), kept outside the composition so the call survives the call
 * screen being minimised, dismissed or recreated. The screen only renders [snapshot].
 *
 * Audio routing is done by [CallAudioRoute]; LiveKit's own audio handler is switched off.
 */
class LiveKitCallSession(
    context: Context,
    private val token: String,
    private val livekitUrl: String,
    val isVideo: Boolean,
    val isGroup: Boolean,
    private val clientProvider: () -> XMPPClient?
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One EGL context per call, shared by the room and every video renderer. */
    val eglBase: EglBase = EglBase.create()
    val room: Room = LiveKit.create(
        appContext,
        RoomOptions(),
        LiveKitOverrides(
            eglBase = eglBase,
            audioOptions = AudioOptions(audioHandler = NoAudioHandler())
        )
    )

    private val _snapshot = MutableStateFlow(LiveKitCallSnapshot())
    val snapshot: StateFlow<LiveKitCallSnapshot> = _snapshot.asStateFlow()

    private var started = false
    private var disposed = false
    private var eventsJob: Job? = null
    private var micOn = true
    private var cameraOn = false
    private var facing = CameraPosition.FRONT

    /** Connects and turns the allowed devices on. A refused device does not fail the call. */
    fun start(micAllowed: Boolean, cameraAllowed: Boolean, startWithMicOn: Boolean, startWithCameraOn: Boolean) {
        if (started || disposed) return
        started = true
        micOn = micAllowed && startWithMicOn
        cameraOn = isVideo && cameraAllowed && startWithCameraOn
        eventsJob = scope.launch { room.events.collect { onEvent(it) } }
        scope.launch {
            try {
                room.connect(livekitUrl, token, ConnectOptions(autoSubscribe = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallService.reportError(e.message ?: "Could not join the call")
                return@launch
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
            }
            publish(connected = true)
            CallService.markConnected()
        }
    }

    private fun onEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantDisconnected -> {
                // A 1:1 call ends when the other person leaves; a group call goes on while anybody remains.
                if (!isGroup && room.remoteParticipants.isEmpty()) {
                    CallService.hangUp(clientProvider())
                    return
                }
            }
            is RoomEvent.Disconnected -> {
                if (event.error != null) CallService.reportError("Call disconnected") else CallService.hangUp(clientProvider())
                return
            }
            else -> Unit
        }
        publish()
    }

    private fun idOf(p: Participant): String = p.identity?.value ?: p.sid.value

    private fun cameraOf(p: Participant): VideoTrack? {
        val publication = p.getTrackPublication(Track.Source.CAMERA) ?: return null
        if (publication.muted) return null
        return publication.track as? VideoTrack
    }

    private fun publish(connected: Boolean = _snapshot.value.connected) {
        if (disposed) return
        val local = room.localParticipant
        val others = room.remoteParticipants.values.sortedBy { it.joinedAt ?: 0L }
        val speaking = room.activeSpeakers.map { idOf(it) }.toSet()
        val videos = LinkedHashMap<String, VideoTrack>()
        val inputs = (listOf<Participant>(local) + others).map { p ->
            val id = idOf(p)
            val video = cameraOf(p)
            if (video != null) videos[id] = video
            GroupTileInput(
                id = id,
                name = p.name?.takeIf { it.isNotBlank() } ?: p.identity?.value.orEmpty().ifBlank { if (p === local) "You" else "Participant" },
                isLocal = p === local,
                speaking = id in speaking || p.isSpeaking,
                hasVideo = video != null,
                micMuted = !p.isMicrophoneEnabled
            )
        }
        _snapshot.value = LiveKitCallSnapshot(
            connected = connected,
            micOn = micOn,
            cameraOn = cameraOn,
            facing = facing,
            participants = inputs,
            videoById = videos,
            remoteVideo = others.firstNotNullOfOrNull { cameraOf(it) },
            localVideo = cameraOf(local)
        )
    }

    fun toggleMic() {
        scope.launch {
            val next = !micOn
            try {
                room.localParticipant.setMicrophoneEnabled(next)
                micOn = next
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Device unavailable: keep the current state.
            }
        }
    }

    fun toggleCamera() {
        scope.launch {
            val next = !cameraOn
            try {
                room.localParticipant.setCameraEnabled(next)
                cameraOn = next
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Camera unavailable: keep the current state.
            }
        }
    }

    fun flipCamera() {
        val next = if (facing == CameraPosition.FRONT) CameraPosition.BACK else CameraPosition.FRONT
        val track = cameraOf(room.localParticipant) as? LocalVideoTrack ?: return
        try {
            // Empty device id: the SDK picks the camera for this position.
            track.switchCamera("", next)
            facing = next
            publish()
        } catch (_: Exception) {
            // Switching failed: the current camera keeps running.
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        eventsJob?.cancel()
        try {
            room.disconnect()
            room.release()
        } catch (_: Exception) {
            // Already torn down.
        }
        try {
            eglBase.release()
        } catch (_: Exception) {
        }
        scope.cancel()
    }
}

/** The one active LiveKit call, looked up by key so a recreated screen finds it again. */
object LiveKitRuntime {
    private val lock = Any()
    private var key: String? = null
    private var active: LiveKitCallSession? = null

    init {
        CallStore.addReleaseHook { disposeActive() }
    }

    fun acquire(newKey: String, create: () -> LiveKitCallSession): LiveKitCallSession = synchronized(lock) {
        val current = active
        if (current != null && key == newKey) return current
        current?.dispose()
        val session = create()
        key = newKey
        active = session
        session
    }

    fun active(): LiveKitCallSession? = synchronized(lock) { active }

    /** The call is over (hang up, decline, error): release the room and the devices. */
    fun disposeActive() = synchronized(lock) {
        active?.dispose()
        active = null
        key = null
    }
}
