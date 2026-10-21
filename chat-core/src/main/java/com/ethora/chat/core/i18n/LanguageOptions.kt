package com.ethora.chat.core.i18n

/** One selectable language: BCP-47 [id] and the language's own [name]. */
data class LanguageOption(val id: String, val name: String)

/**
 * The languages the language picker offers by default. Port of web `LANGUAGE_OPTIONS`:
 * regional ids matching the deployment's reader regions.
 */
val LANGUAGE_OPTIONS: List<LanguageOption> = listOf(
    LanguageOption(id = "en-CA", name = "English"),
    LanguageOption(id = "es-US", name = "Spanish"),
    LanguageOption(id = "fr-CA", name = "Français")
)

/** Names used when a language is not in [LANGUAGE_OPTIONS]: each language in its own words. */
private val AUTONYMS: Map<String, String> = mapOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "pt" to "Português",
    "ht" to "Kreyòl ayisyen",
    "zh" to "中文",
    "de" to "Deutsch",
    "it" to "Italiano",
    "uk" to "Українська",
    "ru" to "Русский",
    "pl" to "Polski",
    "tr" to "Türkçe",
    "ar" to "العربية",
    "hi" to "हिन्दी",
    "ja" to "日本語",
    "ko" to "한국어",
    "nl" to "Nederlands",
    "sv" to "Svenska"
)

/** The name to show for a language tag: the picker's name when it has one, else the autonym. */
fun languageLabel(id: String): String {
    LANGUAGE_OPTIONS.firstOrNull { it.id.equals(id, ignoreCase = true) }?.let { return it.name }
    return AUTONYMS[id.substringBefore('-').lowercase()] ?: id
}

/**
 * Choices for a language card. [configured] tags (from `settings.languages.*`) replace the
 * built-in list when at least one is a valid tag. Port of the React Native `languageSettings.ts`.
 */
fun languageChoices(configured: List<String>?): List<LanguageOption> {
    val list = configured.orEmpty()
        .mapNotNull { canonicalLocale(it) }
        .distinct()
        .map { LanguageOption(it, languageLabel(it)) }
    return list.ifEmpty { LANGUAGE_OPTIONS }
}
