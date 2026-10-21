package com.ethora.chat.core.models

/**
 * Message translation model. One entry per target language, as the server attaches them in the
 * `<translations>` element (see `MessageTranslationXml`).
 */
data class MessageTranslation(
    val translatedText: String,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val languageName: String? = null
)
