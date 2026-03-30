package com.ethora.samplechatapp

import android.app.Application

/**
 * Stores process-level flags so MainActivity recreation does not start a second FCM chain.
 */
class EthoraApplication : Application() {
    companion object {
        @Volatile
        var fcmRegistrationScheduled: Boolean = false
    }
}
