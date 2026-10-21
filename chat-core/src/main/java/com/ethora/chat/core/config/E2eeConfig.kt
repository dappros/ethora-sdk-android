package com.ethora.chat.core.config

/**
 * End-to-end encryption (OMEMO 2). Off by default, and off means nothing changes:
 * no keys are generated and stanzas are built exactly as before. With it on, the
 * SDK publishes this device's keys after connecting and encrypts messages in rooms
 * the backend marked `e2ee`. Other rooms stay plain.
 */
data class E2eeConfig(
    val enabled: Boolean = false
)
