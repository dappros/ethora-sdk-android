package com.ethora.chat.core.models

/**
 * Message translation model
 */
data class MessageTranslation(
    val translatedText: String,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null
)
