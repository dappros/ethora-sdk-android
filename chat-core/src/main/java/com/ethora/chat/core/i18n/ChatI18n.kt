package com.ethora.chat.core.i18n

import java.util.concurrent.ConcurrentHashMap

/**
 * UI string resolution for the SDK's own captions. Port of web `src/i18n/strings.ts`:
 * the same keys, the same `{name}` placeholders, and the same rules.
 *
 *  - The locale reduces to its base language (`fr-CA` -> `fr`).
 *  - Keys come from the built-in table for that base language, falling back to English
 *    for any key it lacks, and an unknown language falls back to English entirely.
 *  - Host overrides (`ChatConfig.i18n.strings`) win over the built-in table.
 *  - A key missing from every table renders as the raw key, so nothing renders blank.
 */
object ChatI18n {
    const val DEFAULT_UI_LANGUAGE = "en"

    /** Base languages that ship a built-in caption table. */
    val BUILTIN_LANGUAGES: List<String> = listOf("en", "fr", "es", "pt", "ht", "zh")

    private val builtinCache = ConcurrentHashMap<String, Map<String, String>>()

    private fun builtinTable(base: String): Map<String, String>? {
        if (base !in BUILTIN_LANGUAGES) return null
        return builtinCache.getOrPut(base) { UiStringTables.byLanguage.getValue(base)() }
    }

    /** Reduce a BCP-47 locale ("fr-CA") to its base language ("fr"), lowercased. */
    fun toBaseLanguage(locale: String?): String {
        val raw = if (locale.isNullOrBlank()) DEFAULT_UI_LANGUAGE else locale
        return raw.substringBefore('-').trim().lowercase().ifEmpty { DEFAULT_UI_LANGUAGE }
    }

    /**
     * The effective string table for a locale: the built-in table for its base language,
     * English for any key it lacks, then host [overrides] merged on top.
     */
    fun resolveStringTable(
        locale: String?,
        overrides: Map<String, String>? = null
    ): Map<String, String> {
        val english = builtinTable(DEFAULT_UI_LANGUAGE).orEmpty()
        val base = builtinTable(toBaseLanguage(locale)) ?: english
        return if (overrides.isNullOrEmpty()) english + base else english + base + overrides
    }

    /** Replace `{name}` placeholders with values from [vars]. Unknown placeholders stay as-is. */
    fun interpolate(template: String, vars: Map<String, Any>? = null): String {
        if (vars.isNullOrEmpty()) return template
        return PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            if (vars.containsKey(name)) vars.getValue(name).toString() else match.value
        }
    }

    /** One-shot translate for non-Compose code. Missing keys render as the key itself. */
    fun translateKey(
        key: String,
        locale: String?,
        overrides: Map<String, String>? = null,
        vars: Map<String, Any>? = null
    ): String = interpolate(resolveStringTable(locale, overrides)[key] ?: key, vars)

    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")
}
