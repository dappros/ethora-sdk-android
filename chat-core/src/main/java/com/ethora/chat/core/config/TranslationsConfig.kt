package com.ethora.chat.core.config

/**
 * ISO 639-1 language codes
 */
enum class Iso639_1Code(val code: String) {
    EN("en"),
    ES("es"),
    PT("pt"),
    HT("ht"),
    FR("fr"),
    ZH("zh")
}

/** How a translated message is shown: inline ("auto") or behind a Translate link ("manual"). */
enum class TranslateMode(val value: String) {
    AUTO("auto"),
    MANUAL("manual")
}

/**
 * Translations configuration. Mirrors `config.translates` of the web component.
 *
 * Translation itself runs on the server: incoming messages carry their translations inside the
 * stanza, and outgoing text is tagged with `<translate source="..."/>`.
 *
 * @param enabled Turns message translation on. Off means no tag on outgoing text and no
 *   translation UI.
 * @param translations Legacy ISO 639-1 field, kept for API compatibility.
 * @param mode Default display mode. Default [TranslateMode.AUTO].
 * @param forceType Pins [mode] as host policy: the reader cannot switch it.
 * @param readerLocale Reader's BCP-47 locale. Drives the translation target. Unset, the reader
 *   chooses it through the language selector.
 * @param showLanguageSelector Shows the globe language picker in the chat header. Default true.
 * @param showLanguageList Shows the language list inside that picker. Default true.
 */
data class TranslationsConfig(
    val enabled: Boolean,
    val translations: Iso639_1Code? = null,
    val mode: TranslateMode? = null,
    val forceType: Boolean? = null,
    val readerLocale: String? = null,
    val showLanguageSelector: Boolean? = null,
    val showLanguageList: Boolean? = null
)
