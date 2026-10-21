package com.ethora.chat.ui.call

import android.content.Context
import com.ethora.chat.core.call.CallAudioRouter
import com.ethora.chat.core.call.CallStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI state of the active call that outlives the call screen. */
object CallUi {
    private val _minimized = MutableStateFlow(false)

    /** The call screen is collapsed to a strip; the call itself keeps running. */
    val minimized: StateFlow<Boolean> = _minimized.asStateFlow()

    private val _permissionsEpoch = MutableStateFlow(0)

    /** Bumped when the user answered a camera or microphone prompt (the foreground service type depends on them). */
    val permissionsEpoch: StateFlow<Int> = _permissionsEpoch.asStateFlow()

    init {
        CallStore.addReleaseHook { _minimized.value = false }
    }

    fun setMinimized(value: Boolean) {
        _minimized.value = value
    }

    fun permissionsChanged() {
        _permissionsEpoch.value = _permissionsEpoch.value + 1
    }
}

/** Owns the [CallAudioRouter] of the active call; released with the call. */
object CallAudioRoute {
    private val _router = MutableStateFlow<CallAudioRouter?>(null)
    val router: StateFlow<CallAudioRouter?> = _router.asStateFlow()

    init {
        CallStore.addReleaseHook { stop() }
    }

    /** Starts routing for the call (idempotent). */
    @Synchronized
    fun start(context: Context, isVideo: Boolean) {
        if (_router.value != null) return
        val created = CallAudioRouter(context)
        created.start(isVideo)
        _router.value = created
    }

    @Synchronized
    fun stop() {
        _router.value?.stop()
        _router.value = null
    }
}
