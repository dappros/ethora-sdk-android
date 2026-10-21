package com.ethora.chat.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** What the user allowed for a call. A refusal never fails the call. */
data class CallPermissionState(val resolved: Boolean, val mic: Boolean, val camera: Boolean)

/**
 * Asks, once per call, for the devices the call needs (microphone, camera for video) and for
 * the optional extras (notifications for the ongoing-call notification, Bluetooth for a
 * headset). [resolved] turns true after the prompt, whatever the answer.
 */
@Composable
fun rememberCallPermissions(key: Any, isVideo: Boolean): CallPermissionState {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext }
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    var state by remember(key) { mutableStateOf(CallPermissionState(false, false, false)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state = CallPermissionState(
            resolved = true,
            mic = granted(Manifest.permission.RECORD_AUDIO),
            camera = granted(Manifest.permission.CAMERA)
        )
        CallUi.permissionsChanged()
    }
    LaunchedEffect(key) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (isVideo) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = wanted.filter { !granted(it) }
        if (missing.isEmpty()) {
            state = CallPermissionState(true, mic = true, camera = isVideo)
            CallUi.permissionsChanged()
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }
    return state
}
