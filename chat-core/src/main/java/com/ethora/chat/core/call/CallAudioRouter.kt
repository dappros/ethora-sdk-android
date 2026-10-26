package com.ethora.chat.core.call

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the call audio plays. */
enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

/**
 * Pure rules for choosing and cycling the audio route; the platform work is in
 * [CallAudioRouter].
 */
object AudioRoutePolicy {
    /** Order the toggle cycles through. */
    private val ORDER = listOf(AudioRoute.BLUETOOTH, AudioRoute.WIRED, AudioRoute.EARPIECE, AudioRoute.SPEAKER)

    /**
     * What a call starts on: a connected headset first, otherwise the loudspeaker for a video
     * call and the earpiece for an audio call (the loudspeaker on a device with no earpiece).
     */
    fun defaultRoute(isVideo: Boolean, available: Collection<AudioRoute>): AudioRoute = when {
        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
        AudioRoute.WIRED in available -> AudioRoute.WIRED
        isVideo || AudioRoute.EARPIECE !in available -> AudioRoute.SPEAKER
        else -> AudioRoute.EARPIECE
    }

    /** The route after one tap of the toggle: the next available one, wrapping around. */
    fun next(current: AudioRoute, available: Collection<AudioRoute>): AudioRoute {
        val choices = ORDER.filter { it in available }
        if (choices.isEmpty()) return current
        val index = choices.indexOf(current)
        return choices[(index + 1) % choices.size]
    }

    /**
     * The route to be on after the set of devices changed: the user's own choice while it is
     * still there, otherwise the default (a headset that disconnected falls back, one that
     * connected is taken, unless the user picked something else on purpose).
     */
    fun afterDevicesChanged(
        current: AudioRoute,
        userChoice: AudioRoute?,
        isVideo: Boolean,
        available: Collection<AudioRoute>
    ): AudioRoute = when {
        userChoice != null && userChoice in available -> userChoice
        current in available && (current != AudioRoute.EARPIECE && current != AudioRoute.SPEAKER) -> current
        else -> defaultRoute(isVideo, available)
    }
}

/**
 * Routes the audio of a call between the earpiece, the loudspeaker, a Bluetooth headset and
 * wired headphones. One instance per call: [start] when the media session begins, [stop] when
 * it is over. LiveKit's own audio handler is switched off for the call so the two do not
 * fight over the audio mode.
 */
class CallAudioRouter(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _available = MutableStateFlow(listOf(AudioRoute.SPEAKER))
    val available: StateFlow<List<AudioRoute>> = _available.asStateFlow()

    private val _selected = MutableStateFlow(AudioRoute.SPEAKER)
    val selected: StateFlow<AudioRoute> = _selected.asStateFlow()

    private var isVideo = false
    private var userChoice: AudioRoute? = null
    private var started = false
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    private var focusRequest: AudioFocusRequest? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    @Synchronized
    fun start(video: Boolean) {
        if (started) return
        started = true
        isVideo = video
        userChoice = null
        previousMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeaker = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        val routes = currentRoutes()
        _available.value = routes
        apply(AudioRoutePolicy.defaultRoute(video, routes))
    }

    /** Switches to [route] (ignored when it is not available) and remembers it as the user's choice. */
    @Synchronized
    fun select(route: AudioRoute) {
        if (!started || route !in _available.value) return
        userChoice = route
        apply(route)
    }

    /** One tap of the route button. */
    fun toggle() = select(AudioRoutePolicy.next(_selected.value, _available.value))

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            previousSpeaker?.let { audioManager.isSpeakerphoneOn = it }
        }
        previousMode?.let { audioManager.mode = it }
        previousMode = null
        previousSpeaker = null
        abandonFocus()
    }

    @Synchronized
    private fun refresh() {
        if (!started) return
        val routes = currentRoutes()
        _available.value = routes
        val next = AudioRoutePolicy.afterDevicesChanged(_selected.value, userChoice, isVideo, routes)
        if (next != _selected.value) apply(next)
    }

    private fun currentRoutes(): List<AudioRoute> {
        val found = LinkedHashSet<AudioRoute>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            routeOf(device.type)?.let { found.add(it) }
        }
        // A phone always has the loudspeaker; the earpiece only when it reports one.
        found.add(AudioRoute.SPEAKER)
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            found.remove(AudioRoute.EARPIECE)
        }
        return listOf(AudioRoute.BLUETOOTH, AudioRoute.WIRED, AudioRoute.EARPIECE, AudioRoute.SPEAKER).filter { it in found }
    }

    private fun routeOf(type: Int): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioRoute.BLUETOOTH else null
    }

    private fun apply(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = audioManager.availableCommunicationDevices.firstOrNull { routeOf(it.type) == route }
            if (wanted != null) audioManager.setCommunicationDevice(wanted)
        } else {
            @Suppress("DEPRECATION")
            when (route) {
                AudioRoute.SPEAKER -> {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.isSpeakerphoneOn = true
                }
                AudioRoute.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                AudioRoute.EARPIECE, AudioRoute.WIRED -> {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.isSpeakerphoneOn = false
                }
            }
        }
        _selected.value = route
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
