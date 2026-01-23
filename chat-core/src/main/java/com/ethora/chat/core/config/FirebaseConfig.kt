package com.ethora.chat.core.config

/**
 * Firebase configuration for Google login
 */
data class FirebaseConfig(
    val apiKey: String,
    val authDomain: String,
    val projectId: String,
    val storageBucket: String,
    val messagingSenderId: String,
    val appId: String
)
