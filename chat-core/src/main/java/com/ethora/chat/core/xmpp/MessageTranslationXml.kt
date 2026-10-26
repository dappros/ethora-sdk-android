package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.MessageTranslation
import com.google.gson.JsonParser

/**
 * Translation stanza pieces. Wire-compatible with web `getDataFromXml` and
 * `sendTextMessageWithTranslateTag`:
 *
 *  - Incoming: `<translations value='{"translates":[{"translatedText":"...","language":"fr","languageName":"Francais"}]}'/>`
 *    lands in `Message.translations`, keyed by `language`. Entries missing any of the three
 *    strings are dropped.
 *  - Outgoing: `<translate source="en-CA"/>` declares the language of the text. Translation is
 *    done by the server, per reader, so the sender never pre-translates.
 */
internal object MessageTranslationXml {
    private val translationsTag = Regex("<translations\\b[^>]*>")
    // `\s` after the name keeps `<translations>` from matching.
    private val translateTag = Regex("<translate\\s[^>]*>")

    /** Parsed translations keyed by target language. Null when the stanza has no `<translations>`. */
    fun parseTranslations(xml: String): Map<String, MessageTranslation>? {
        val tag = translationsTag.find(xml)?.value ?: return null
        val json = extractAttribute(tag, "value") ?: return null
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return emptyMap()
            val entries = root.asJsonObject.get("translates")
            if (entries == null || !entries.isJsonArray) return emptyMap()
            val result = linkedMapOf<String, MessageTranslation>()
            for (entry in entries.asJsonArray) {
                if (!entry.isJsonObject) continue
                val obj = entry.asJsonObject
                val text = stringField(obj, "translatedText") ?: continue
                val language = stringField(obj, "language") ?: continue
                val languageName = stringField(obj, "languageName") ?: continue
                result[language] = MessageTranslation(
                    translatedText = text,
                    targetLanguage = language,
                    languageName = languageName
                )
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** The declared source language from `<translate source="..."/>`, or null when absent. */
    fun parseSource(xml: String): String? {
        val tag = translateTag.find(xml)?.value ?: return null
        return extractAttribute(tag, "source")?.takeIf { it.isNotBlank() }
    }

    /** The `<translate>` element to append to an outgoing text stanza. */
    fun translateElement(source: String): String =
        "<translate source=\"${escapeAttribute(source)}\"/>"

    private fun stringField(obj: com.google.gson.JsonObject, name: String): String? {
        val value = obj.get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
