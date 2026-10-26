package com.ethora.chat.core.config

/**
 * 1:1 audio and video calls on LiveKit. Mirrors web `config.videoCalls`
 * (ethora-chat-component `VideoCallsConfig`) so an Android user can call a
 * web user in the same room.
 *
 * Calls are off by default: nothing is shown or signalled unless [enabled] is
 * true and [livekitUrl] is set.
 */
data class VideoCallsConfig(
    val enabled: Boolean = false,
    /** LiveKit server URL, e.g. `wss://your-project.livekit.cloud`. */
    val livekitUrl: String = "",
    /** Room types that can host a call. Web default: `["private"]`. */
    val allowedRoomTypes: List<String> = listOf("private"),
    /** Show the audio-only call button next to the video call button. Off by default. */
    val enableAudioCalls: Boolean = false,
    /** Start a video call with the camera already on. Default true. */
    val startWithCameraOn: Boolean = true,
    /** Start a call with the microphone already on. Default true. */
    val startWithMicOn: Boolean = true
)
