package com.ethora.chat.core.config

/**
 * ISO 639-1 language codes
 */
enum class Iso639_1Code(val code: String) {
    EN("en"),
    ES("es"),
    PT("pt"),
    HT("ht"),
    ZH("zh")
}

/**
 * Translations configuration
 */
data class TranslationsConfig(
    val enabled: Boolean,
    val translations: Iso639_1Code? = null
)
