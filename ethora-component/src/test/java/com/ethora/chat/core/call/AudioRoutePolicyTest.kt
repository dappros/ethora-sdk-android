package com.ethora.chat.core.call

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRoutePolicyTest {
    private val phone = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)

    @Test
    fun `a call starts on the earpiece for audio and the loudspeaker for video`() {
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.defaultRoute(false, phone))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.defaultRoute(true, phone))
    }

    @Test
    fun `a device without an earpiece uses the loudspeaker`() {
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.defaultRoute(false, listOf(AudioRoute.SPEAKER)))
    }

    @Test
    fun `a headset wins over the built-in routes, bluetooth over wired`() {
        assertEquals(AudioRoute.WIRED, AudioRoutePolicy.defaultRoute(true, phone + AudioRoute.WIRED))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoutePolicy.defaultRoute(false, phone + AudioRoute.WIRED + AudioRoute.BLUETOOTH))
    }

    @Test
    fun `the toggle cycles through the available routes and wraps`() {
        val all = listOf(AudioRoute.BLUETOOTH, AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.next(AudioRoute.BLUETOOTH, all))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.next(AudioRoute.EARPIECE, all))
        assertEquals(AudioRoute.BLUETOOTH, AudioRoutePolicy.next(AudioRoute.SPEAKER, all))
        assertEquals("a route that vanished moves to the first choice", AudioRoute.BLUETOOTH, AudioRoutePolicy.next(AudioRoute.WIRED, all))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.next(AudioRoute.SPEAKER, listOf(AudioRoute.SPEAKER)))
    }

    @Test
    fun `a headset that connects is taken and one that disconnects falls back`() {
        assertEquals(
            AudioRoute.BLUETOOTH,
            AudioRoutePolicy.afterDevicesChanged(AudioRoute.EARPIECE, null, false, phone + AudioRoute.BLUETOOTH)
        )
        assertEquals(
            AudioRoute.EARPIECE,
            AudioRoutePolicy.afterDevicesChanged(AudioRoute.BLUETOOTH, null, false, phone)
        )
    }

    @Test
    fun `the user's own choice sticks while it is still available`() {
        assertEquals(
            AudioRoute.SPEAKER,
            AudioRoutePolicy.afterDevicesChanged(AudioRoute.SPEAKER, AudioRoute.SPEAKER, false, phone + AudioRoute.BLUETOOTH)
        )
        assertEquals(
            AudioRoute.EARPIECE,
            AudioRoutePolicy.afterDevicesChanged(AudioRoute.BLUETOOTH, AudioRoute.BLUETOOTH, false, phone)
        )
    }
}
