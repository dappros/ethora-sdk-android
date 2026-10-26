package com.ethora.chat.core.call.p2p

import android.content.Context
import android.os.SystemClock
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.IceServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One peer to peer call, from opening the devices to hang-up. Port of web
 * `calls/p2p/P2PCallController.ts`: the decisions live in the small pure classes next to it
 * ([P2PNegotiator], [IceRestartPolicy], [P2PSignalStanza]); this class only wires them to a
 * real WebRTC peer connection and cleans up after itself.
 *
 * Media encryption: a WebRTC peer connection already encrypts media between the two peers
 * with DTLS-SRTP, so the LiveKit insertable-streams E2EE layer is deliberately NOT applied
 * on this path.
 *
 * Threading: everything runs on one private thread (a serial executor). Public functions
 * may be called from any thread, they post to it. [events] callbacks run on that thread.
 */
enum class P2PStatus { PREPARING, WAITING, CONNECTING, CONNECTED, RECONNECTING, FAILED, ENDED }

data class P2PSnapshot(
    val status: P2PStatus = P2PStatus.PREPARING,
    val micEnabled: Boolean = true,
    val camEnabled: Boolean = false,
    val frontCamera: Boolean = true,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
    /** The peer's video is actually delivering frames (false when their camera is off). */
    val remoteVideoLive: Boolean = false,
    /** Dismissible, non-fatal hint (e.g. the camera could not be opened). */
    val notice: String? = null
)

data class P2PCallParams(
    val callId: String,
    val role: P2PRole,
    val kind: CallKind,
    /** Peer's xmpp localpart; signals from anyone else are ignored. */
    val peerLocal: String,
    val iceServers: List<IceServerConfig>,
    val startWithMicOn: Boolean = true,
    val startWithCameraOn: Boolean = true
)

interface P2PCallDeps {
    /** Fresh credentials; null/empty keeps the servers already in use. */
    suspend fun fetchIceServers(): List<IceServerConfig>?

    fun sendSignal(signal: P2PSignalName, payload: P2PPayload)

    fun log(message: String, error: Throwable? = null) {}
}

class P2PCallEvents(
    /** The offer/answer exchange completed: media is now negotiating. */
    val onNegotiated: () -> Unit = {},
    /** Media is flowing for the first time. */
    val onConnected: () -> Unit = {},
    /** The peer ended the call (hang up, decline, their own failure). */
    val onRemoteEnd: (reason: String) -> Unit = {},
    /** The call failed locally; the message is meant for the user. */
    val onFailed: (message: String) -> Unit = {},
    /** Called exactly once when the call's resources are released. */
    val onDisposed: (started: Boolean) -> Unit = {}
)

data class P2PTimings(
    val connectTimeoutMs: Long = 25_000,
    // While nobody has answered, the offer and candidates are sent again: a callee that was
    // still reconnecting (woken by push) missed the first copy, and the stanzas are not
    // stored for later. Duplicates are no-ops there.
    val offerResendMs: List<Long> = listOf(4_000, 10_000)
)

class P2PCallController(
    context: Context,
    private val params: P2PCallParams,
    private val deps: P2PCallDeps,
    private val events: P2PCallEvents = P2PCallEvents(),
    private val timings: P2PTimings = P2PTimings()
) {
    val callId: String = params.callId
    val role: P2PRole = params.role
    val kind: CallKind = params.kind

    private val appContext: Context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "p2p-call").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val negotiator = P2PNegotiator(params.callId, params.role, params.peerLocal)
    private val policy = IceRestartPolicy()
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _snapshot = MutableStateFlow(
        P2PSnapshot(
            micEnabled = params.startWithMicOn,
            camEnabled = false
        )
    )
    val snapshot: StateFlow<P2PSnapshot> = _snapshot.asStateFlow()

    /** Shared EGL context for the video renderers of this call. */
    val eglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var rtcConfig: PeerConnection.RTCConfiguration? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var cameraBusy = false

    private var remoteVideoTrack: VideoTrack? = null
    @Volatile
    private var lastRemoteFrameAt = 0L
    private val remoteFrameSink = VideoSink { lastRemoteFrameAt = SystemClock.elapsedRealtime() }
    private var remoteTicker: Job? = null

    private val timers = HashSet<Job>()
    private var graceTimer: Job? = null
    private var deadlineTimer: Job? = null
    private var connectTimer: Job? = null

    private val started = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val endSent = AtomicBoolean(false)
    private var connectedOnce = false
    private var negotiated = false
    private var lastConn: P2PConnState? = null

    // Outgoing candidates wait until the offer/answer they belong to is out, so the peer
    // never sees a candidate before its description.
    private var candidateGateOpen = false
    private val heldOutgoingCandidates = mutableListOf<P2PPayload.Candidate>()
    private var sentCandidates = mutableListOf<P2PPayload.Candidate>()
    private var lastOffer: P2PPayload.Sdp? = null

    init {
        scope.launch {
            for (job in jobs) {
                if (disposed.get()) continue
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("negotiation step failed", e)
                    // A broken negotiation cannot recover on its own.
                    fail("Could not set up the call", "negotiation-error")
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun log(message: String, error: Throwable? = null) = deps.log("[p2p $callId] $message", error)

    private fun enqueue(job: suspend () -> Unit) {
        jobs.trySend(job)
    }

    private fun later(ms: Long, fn: () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch {
            delay(ms)
            timers.remove(job)
            if (!disposed.get()) fn()
        }
        timers.add(job)
        return job
    }

    private fun cancel(job: Job?) {
        if (job == null) return
        job.cancel()
        timers.remove(job)
    }

    private fun update(change: (P2PSnapshot) -> P2PSnapshot) = _snapshot.update(change)

    // ---- lifecycle ----------------------------------------------------------

    /**
     * Caller: open the devices, build the connection, offer. Callee: open the devices, build
     * the connection, then apply the offer that may already be waiting. Safe to call once;
     * every failure ends the call with a message instead of throwing.
     */
    fun start() {
        if (disposed.get() || !started.compareAndSet(false, true)) return
        scope.launch { doStart() }
    }

    private suspend fun doStart() {
        update { it.copy(status = P2PStatus.PREPARING) }

        // Credentials are fetched while the devices open.
        val icePromise: Deferred<List<IceServerConfig>> =
            if (params.iceServers.isNotEmpty()) {
                CompletableDeferred(params.iceServers)
            } else {
                scope.async {
                    try {
                        deps.fetchIceServers().orEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }

        try {
            acquireMedia()
        } catch (e: Exception) {
            log("opening the devices failed", e)
            fail(describeMediaError(e), "media-error")
            return
        }
        if (disposed.get()) return

        val servers = icePromise.await()
        if (disposed.get()) return

        try {
            createConnection(servers)
        } catch (e: Exception) {
            log("peer connection setup failed", e)
            fail("Could not start the call", "setup-error")
            return
        }

        enqueue {
            runCommands(negotiator.markReady())
            if (role == P2PRole.CALLER && !disposed.get()) {
                sendOffer(iceRestart = false)
                update { it.copy(status = P2PStatus.WAITING) }
                scheduleOfferResends()
            }
        }
    }

    /** Feed a signal from the peer (already routed by callId). */
    fun receive(signal: P2PSignal) {
        if (disposed.get()) return
        scope.launch {
            if (disposed.get()) return@launch
            val commands = negotiator.receive(signal)
            // An end must act immediately: it can arrive while the devices are still opening,
            // before the connection (and so the job queue) is running.
            val end = commands.firstOrNull { it is NegotiatorCommand.RemoteEnd } as? NegotiatorCommand.RemoteEnd
            if (end != null) {
                handleRemoteEnd(end.reason)
                return@launch
            }
            if (commands.isNotEmpty()) enqueue { runCommands(commands) }
        }
    }

    /** Local user hung up (or cancelled): tell the peer and release everything. */
    fun hangup(reason: String = "hangup") {
        if (disposed.get()) return
        sendEnd(reason)
        dispose()
    }

    /**
     * Release every resource: timers, listeners, media, the connection. Idempotent, safe from
     * any thread and any exit path. Tells the peer unless it is already known to be over.
     */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        scope.launch { teardown() }
    }

    private suspend fun teardown() {
        try {
            if (started.get() && !negotiator.isEnded) sendEnd("hangup")

            for (job in timers.toList()) job.cancel()
            timers.clear()
            remoteTicker = null
            negotiator.stop()
            jobs.close()

            remoteVideoTrack?.let {
                try { it.removeSink(remoteFrameSink) } catch (_: Exception) { /* track already gone */ }
            }
            remoteVideoTrack = null

            val connection = pc
            pc = null
            if (connection != null) {
                try {
                    connection.close()
                    connection.dispose()
                } catch (e: Exception) {
                    log("closing the connection failed", e)
                }
            }
            releaseMedia()
            try {
                factory?.dispose()
            } catch (_: Exception) {
            }
            factory = null
            try {
                audioDeviceModule?.release()
            } catch (_: Exception) {
            }
            audioDeviceModule = null
            try {
                eglBase.release()
            } catch (_: Exception) {
            }

            _snapshot.update {
                it.copy(
                    status = if (it.status == P2PStatus.FAILED) P2PStatus.FAILED else P2PStatus.ENDED,
                    localVideo = null,
                    remoteVideo = null,
                    remoteVideoLive = false,
                    camEnabled = false
                )
            }
            try {
                events.onDisposed(started.get())
            } catch (e: Exception) {
                log("onDisposed failed", e)
            }
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    private fun sendEnd(reason: String) {
        if (!endSent.compareAndSet(false, true)) return
        try {
            deps.sendSignal(P2PSignalName.END, P2PPayload.End(reason))
        } catch (e: Exception) {
            log("end signal failed", e)
        }
    }

    private fun fail(message: String, reason: String) {
        if (disposed.get()) return
        sendEnd(reason)
        _snapshot.update { it.copy(status = P2PStatus.FAILED, notice = message) }
        try {
            events.onFailed(message)
        } catch (e: Exception) {
            log("onFailed failed", e)
        }
        dispose()
    }

    private fun handleRemoteEnd(reason: String) {
        if (disposed.get()) return
        // The peer already knows; do not echo an end back.
        endSent.set(true)
        _snapshot.update { it.copy(status = P2PStatus.ENDED) }
        try {
            events.onRemoteEnd(reason)
        } catch (e: Exception) {
            log("onRemoteEnd failed", e)
        }
        dispose()
    }

    // ---- media --------------------------------------------------------------

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioDeviceModule = adm
        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    private fun acquireMedia() {
        val f = ensureFactory()

        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = f.createAudioSource(constraints)
        audioSource = source
        audioTrack = f.createAudioTrack("ethora-p2p-audio", source).also { it.setEnabled(params.startWithMicOn) }
        _snapshot.update { it.copy(micEnabled = params.startWithMicOn) }

        if (kind == CallKind.VIDEO && params.startWithCameraOn) {
            try {
                startCamera(front = true)
            } catch (e: Exception) {
                // The call goes on without our video; the peer's video still arrives.
                log("camera could not be opened", e)
                _snapshot.update { it.copy(notice = describeMediaError(e)) }
            }
        }
    }

    private fun startCamera(front: Boolean) {
        val f = ensureFactory()
        if (!Camera2Enumerator.isSupported(appContext)) throw IllegalStateException("No camera found")
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: names.firstOrNull()
            ?: throw IllegalStateException("No camera found")
        val cam = enumerator.createCapturer(name, null) ?: throw IllegalStateException("No camera found")
        val helper = SurfaceTextureHelper.create("ethora-p2p-capture", eglBase.eglBaseContext)
        val source = f.createVideoSource(cam.isScreencast)
        cam.initialize(helper, appContext, source.capturerObserver)
        cam.startCapture(1280, 720, 30)
        val track = f.createVideoTrack("ethora-p2p-video", source)
        capturer = cam
        textureHelper = helper
        videoSource = source
        videoTrack = track
        _snapshot.update {
            it.copy(camEnabled = true, localVideo = track, frontCamera = enumerator.isFrontFacing(name))
        }
    }

    /** Stops the camera and releases the device (the camera light goes out). */
    private fun stopCamera() {
        val cam = capturer
        capturer = null
        try {
            cam?.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log("stopCapture failed", e)
        }
        try { cam?.dispose() } catch (_: Exception) {}
        try { textureHelper?.dispose() } catch (_: Exception) {}
        textureHelper = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
    }

    private fun releaseMedia() {
        stopCamera()
        try { audioTrack?.dispose() } catch (_: Exception) {}
        audioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        videoSender = null
    }

    fun toggleMic() {
        scope.launch {
            val track = audioTrack ?: return@launch
            if (disposed.get()) return@launch
            val next = !track.enabled()
            track.setEnabled(next)
            _snapshot.update { it.copy(micEnabled = next) }
        }
    }

    /**
     * Turning the camera off stops sending video and releases the device; turning it on opens
     * the camera again and swaps it into the existing sender, so no renegotiation is needed.
     */
    fun toggleCamera() {
        scope.launch {
            val sender = videoSender
            if (disposed.get() || kind != CallKind.VIDEO || sender == null || cameraBusy) return@launch
            cameraBusy = true
            try {
                if (videoTrack != null) {
                    sender.setTrack(null, false)
                    stopCamera()
                    _snapshot.update { it.copy(camEnabled = false, localVideo = null, notice = null) }
                } else {
                    startCamera(front = _snapshot.value.frontCamera)
                    sender.setTrack(videoTrack, false)
                }
            } catch (e: Exception) {
                log("camera toggle failed", e)
                _snapshot.update { it.copy(notice = describeMediaError(e)) }
            } finally {
                cameraBusy = false
            }
        }
    }

    fun switchCamera() {
        scope.launch {
            val cam = capturer ?: return@launch
            if (disposed.get()) return@launch
            cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    _snapshot.update { it.copy(frontCamera = isFrontCamera) }
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    log("camera switch failed: $errorDescription")
                }
            })
        }
    }

    fun dismissNotice() {
        _snapshot.update { it.copy(notice = null) }
    }

    // ---- peer connection ----------------------------------------------------

    private fun toIceServer(server: IceServerConfig): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(server.urls)
        server.username?.let { builder.setUsername(it) }
        server.credential?.let { builder.setPassword(it) }
        return builder.createIceServer()
    }

    private fun createConnection(servers: List<IceServerConfig>) {
        val config = PeerConnection.RTCConfiguration(servers.map { toIceServer(it) }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        rtcConfig = config
        val connection = ensureFactory().createPeerConnection(config, observer)
            ?: throw IllegalStateException("Peer connection could not be created")
        pc = connection

        val streamIds = listOf("ethora-p2p")
        audioTrack?.let { connection.addTrack(it, streamIds) }
        if (kind == CallKind.VIDEO) {
            val track = videoTrack
            videoSender = if (track != null) {
                connection.addTrack(track, streamIds)
            } else {
                // Camera starts off: keep a video section so it can be turned on later
                // without renegotiating.
                connection.addTransceiver(
                    livekit.org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                ).sender
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                if (disposed.get() || candidate.sdp.isNullOrEmpty()) return@launch
                val payload = P2PPayload.Candidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                if (candidateGateOpen) emitCandidate(payload) else heldOutgoingCandidates.add(payload)
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            scope.launch {
                if (!disposed.get() && track is VideoTrack) onRemoteVideo(track)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val mapped = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> P2PConnState.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> P2PConnState.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> P2PConnState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> P2PConnState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> P2PConnState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> P2PConnState.CLOSED
            }
            scope.launch { onConnectionState(mapped) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun emitCandidate(candidate: P2PPayload.Candidate) {
        sentCandidates.add(candidate)
        deps.sendSignal(P2PSignalName.CANDIDATE, candidate)
    }

    private fun openCandidateGate() {
        candidateGateOpen = true
        val held = heldOutgoingCandidates.toList()
        heldOutgoingCandidates.clear()
        for (candidate in held) emitCandidate(candidate)
    }

    private fun onRemoteVideo(track: VideoTrack) {
        remoteVideoTrack?.let {
            try { it.removeSink(remoteFrameSink) } catch (_: Exception) { /* replaced */ }
        }
        remoteVideoTrack = track
        track.setEnabled(true)
        track.addSink(remoteFrameSink)
        _snapshot.update { it.copy(remoteVideo = track) }
        if (remoteTicker == null) {
            remoteTicker = scope.launch {
                while (true) {
                    delay(1_000)
                    if (disposed.get()) break
                    val live = remoteVideoTrack != null &&
                        lastRemoteFrameAt != 0L &&
                        SystemClock.elapsedRealtime() - lastRemoteFrameAt < 2_500
                    if (live != _snapshot.value.remoteVideoLive) {
                        _snapshot.update { it.copy(remoteVideoLive = live) }
                    }
                }
            }
        }
    }

    // ---- negotiation --------------------------------------------------------

    private suspend fun sendOffer(iceRestart: Boolean) {
        val connection = pc ?: return
        if (disposed.get()) return
        candidateGateOpen = false
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = connection.createOfferAwait(constraints)
        connection.setLocalAwait(offer)
        if (disposed.get()) return
        val sdp = connection.localDescription?.description ?: offer.description
        negotiator.markLocalOffer()
        val payload = P2PPayload.Sdp("offer", sdp)
        lastOffer = payload
        sentCandidates = mutableListOf()
        deps.sendSignal(P2PSignalName.OFFER, payload)
        openCandidateGate()
    }

    private fun scheduleOfferResends() {
        for (ms in timings.offerResendMs) {
            later(ms) {
                val offer = lastOffer
                if (!negotiator.awaitingAnswer || offer == null) return@later
                log("re-sending offer")
                deps.sendSignal(P2PSignalName.OFFER, offer)
                for (candidate in sentCandidates) deps.sendSignal(P2PSignalName.CANDIDATE, candidate)
            }
        }
    }

    private suspend fun runCommands(commands: List<NegotiatorCommand>) {
        for (command in commands) {
            val connection = pc
            if (disposed.get() || connection == null) return
            when (command) {
                is NegotiatorCommand.ApplyOffer -> applyOffer(command)
                is NegotiatorCommand.ApplyAnswer -> {
                    connection.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, command.sdp))
                    runCommands(negotiator.markRemoteApplied())
                    onNegotiated()
                }
                is NegotiatorCommand.AddCandidate -> {
                    val c = command.candidate
                    try {
                        connection.addIceCandidate(IceCandidate(c.sdpMid.orEmpty(), c.sdpMLineIndex ?: 0, c.candidate))
                    } catch (e: Exception) {
                        // A candidate from a superseded ICE session; harmless.
                        log("addIceCandidate ignored", e)
                    }
                }
                // Handled synchronously in receive(); never queued.
                is NegotiatorCommand.RemoteEnd -> Unit
            }
        }
    }

    private suspend fun applyOffer(command: NegotiatorCommand.ApplyOffer) {
        val connection = pc ?: return
        if (command.rollback && connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            connection.setLocalAwait(SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        }
        connection.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, command.sdp))
        runCommands(negotiator.markRemoteApplied())
        if (disposed.get() || pc == null) return

        candidateGateOpen = false
        val answer = connection.createAnswerAwait(MediaConstraints())
        connection.setLocalAwait(answer)
        if (disposed.get()) return
        deps.sendSignal(
            P2PSignalName.ANSWER,
            P2PPayload.Sdp("answer", connection.localDescription?.description ?: answer.description)
        )
        openCandidateGate()
        if (command.initial) onNegotiated()
    }

    private fun onNegotiated() {
        if (negotiated || disposed.get()) return
        negotiated = true
        if (!connectedOnce) {
            _snapshot.update { it.copy(status = P2PStatus.CONNECTING) }
            connectTimer = later(timings.connectTimeoutMs) {
                if (!connectedOnce) fail("Could not connect the call", "connect-timeout")
            }
        }
        events.onNegotiated()
    }

    // ---- connection health --------------------------------------------------

    private fun onConnectionState(state: P2PConnState) {
        if (disposed.get() || state == lastConn) return
        lastConn = state
        log("connection $state")

        when (state) {
            P2PConnState.CONNECTED -> {
                cancel(graceTimer)
                cancel(deadlineTimer)
                cancel(connectTimer)
                graceTimer = null
                deadlineTimer = null
                connectTimer = null
                runIceAction(policy.onState(state))
                _snapshot.update { it.copy(status = P2PStatus.CONNECTED) }
                if (!connectedOnce) {
                    connectedOnce = true
                    events.onConnected()
                }
            }
            P2PConnState.CONNECTING -> {
                if (connectedOnce) _snapshot.update { it.copy(status = P2PStatus.RECONNECTING) }
            }
            P2PConnState.DISCONNECTED, P2PConnState.FAILED -> {
                if (connectedOnce) _snapshot.update { it.copy(status = P2PStatus.RECONNECTING) }
                runIceAction(policy.onState(state))
            }
            else -> Unit
        }
    }

    private fun runIceAction(action: IceAction) {
        when (action) {
            is IceAction.Wait -> {
                cancel(graceTimer)
                graceTimer = later(action.ms) {
                    graceTimer = null
                    runIceAction(policy.onGraceElapsed())
                }
            }
            is IceAction.Restart -> {
                cancel(graceTimer)
                graceTimer = null
                cancel(deadlineTimer)
                deadlineTimer = later(action.deadlineMs) {
                    deadlineTimer = null
                    val down = lastConn != P2PConnState.CONNECTED
                    runIceAction(policy.onRestartDeadline(down))
                }
                enqueue { restartIce() }
            }
            is IceAction.GiveUp -> fail("Connection lost", "connection-failed")
            IceAction.Recovered, IceAction.None -> Unit
        }
    }

    /** ICE restart with fresh TURN credentials; the policy decides when. */
    private suspend fun restartIce() {
        val connection = pc ?: return
        if (disposed.get()) return
        val fresh = try {
            deps.fetchIceServers()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (disposed.get() || pc == null) return
        val config = rtcConfig
        if (!fresh.isNullOrEmpty() && config != null) {
            try {
                config.iceServers = fresh.map { toIceServer(it) }
                connection.setConfiguration(config)
            } catch (e: Exception) {
                log("setConfiguration failed", e)
            }
        }
        // A negotiation is already in flight (e.g. the peer is restarting too); the restart
        // deadline still guards the outcome.
        if (connection.signalingState() != PeerConnection.SignalingState.STABLE) return
        connection.restartIce()
        sendOffer(iceRestart = true)
    }
}

// ---- SDP observer adapters ---------------------------------------------------

private suspend fun PeerConnection.createOfferAwait(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
            override fun onCreateFailure(error: String?) =
                cont.resumeWithException(IllegalStateException(error ?: "createOffer failed"))
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

private suspend fun PeerConnection.createAnswerAwait(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
            override fun onCreateFailure(error: String?) =
                cont.resumeWithException(IllegalStateException(error ?: "createAnswer failed"))
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

private fun setObserver(onDone: (Throwable?) -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetSuccess() = onDone(null)
    override fun onSetFailure(error: String?) = onDone(IllegalStateException(error ?: "set description failed"))
}

private suspend fun PeerConnection.setLocalAwait(description: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(setObserver { error -> if (error == null) cont.resume(Unit) else cont.resumeWithException(error) }, description)
    }

private suspend fun PeerConnection.setRemoteAwait(description: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(setObserver { error -> if (error == null) cont.resume(Unit) else cont.resumeWithException(error) }, description)
    }
