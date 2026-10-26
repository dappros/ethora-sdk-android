package com.ethora.chat.core.i18n

import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.TranslateMode
import com.ethora.chat.core.config.TranslationsConfig
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.MessageTranslation

/**
 * What the reader sees for one message. Port of web `resolveMessageTranslation`: the one place
 * that decides whether an attached translation is a real one for this reader.
 *
 * @property needsTranslation The message has a known source language, differs from the reader's
 *   base language, and has text with letters. False means there is nothing to reveal.
 * @property hasTranslation A real translation is attached for this reader.
 * @property originalText The text as sent.
 * @property displayText [translatedText] when [hasTranslation], else [originalText].
 */
data class ResolvedMessageTranslation(
    val needsTranslation: Boolean,
    val hasTranslation: Boolean,
    val originalText: String,
    val displayText: String,
    val translatedText: String? = null
)

private val HAS_LETTER = Regex("\\p{L}")

/**
 * Resolve what translation exists for [message] when read in [readerLocale]. Pure, no I/O.
 * A "translation" identical to the original (after trim) counts as none.
 */
fun resolveMessageTranslation(
    message: Message,
    readerLocale: String?
): ResolvedMessageTranslation {
    val originalText = message.body
    val trimmed = originalText.trim()
    val source = message.langSource
    val target = ChatI18n.toBaseLanguage(readerLocale ?: DEFAULT_TARGET)
    val needsTranslation = trimmed.isNotEmpty() &&
        !source.isNullOrBlank() &&
        ChatI18n.toBaseLanguage(source) != target &&
        HAS_LETTER.containsMatchIn(trimmed)

    val attached: String? = if (needsTranslation) {
        val translations: Map<String, MessageTranslation> = message.translations.orEmpty()
        translations[readerLocale.orEmpty()]?.translatedText?.takeIf { it.isNotEmpty() }
            ?: translations[target]?.translatedText?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    val isReal = attached != null && attached.trim() != originalText.trim()
    return ResolvedMessageTranslation(
        needsTranslation = needsTranslation,
        hasTranslation = isReal,
        originalText = originalText,
        displayText = if (isReal) attached!! else originalText,
        translatedText = if (isReal) attached else null
    )
}

private const val DEFAULT_TARGET = "en"

/** Auto/manual mode in effect for this reader. `forceType` pins the host mode. */
fun resolveTranslateMode(
    translates: TranslationsConfig?,
    readerMode: TranslateMode?
): TranslateMode {
    val hostDefault = translates?.mode ?: TranslateMode.AUTO
    if (translates?.forceType == true) return hostDefault
    return readerMode ?: hostDefault
}

/** Whether the reader may switch auto/manual themselves. False when the host pins the mode. */
fun canReaderChooseTranslateMode(translates: TranslationsConfig?): Boolean =
    translates?.forceType != true

/**
 * Whether this reader's outgoing text is tagged with `<translate source="..."/>`. The host must
 * enable translation, and the reader must not have switched sending off. Unset means on.
 */
fun shouldTagOutgoingTranslateSource(
    translatesEnabled: Boolean?,
    translateSendEnabled: Boolean?
): Boolean = translatesEnabled == true && translateSendEnabled != false

/** Reader's locale for translation: `translates.readerLocale`, then `i18n.locale`, then the chat language. */
fun resolveReaderLocale(config: ChatConfig?, chatLanguage: String?): String? =
    config?.translates?.readerLocale?.takeIf { it.isNotBlank() }
        ?: config?.i18n?.locale?.takeIf { it.isNotBlank() }
        ?: chatLanguage?.takeIf { it.isNotBlank() }

/**
 * Locale for UI captions: `i18n.locale`, then the account's app language, then the chat language.
 * Null means "no explicit choice", so captions fall back to English.
 */
fun resolveUiLocale(config: ChatConfig?, appLanguage: String?, chatLanguage: String?): String? =
    config?.i18n?.locale?.takeIf { it.isNotBlank() }
        ?: appLanguage?.takeIf { it.isNotBlank() }
        ?: chatLanguage?.takeIf { it.isNotBlank() }

private val LOCALE_TAG = Regex("^([A-Za-z]{2,3})(?:[-_]([A-Za-z0-9]{2,8}))?$")

/** "en-ca" -> "en-CA", "FR" -> "fr". Anything that is not a language tag gives null. */
fun canonicalLocale(value: String?): String? {
    val match = LOCALE_TAG.find(value.orEmpty().trim()) ?: return null
    val language = match.groupValues[1].lowercase()
    val region = match.groupValues[2]
    if (region.isEmpty()) return language
    val formatted = if (region.length == 2) region.uppercase() else region
    return "$language-$formatted"
}

/** The globe language picker is shown only when translation is on and the host did not hide it. */
fun shouldShowLanguageSelector(translates: TranslationsConfig?): Boolean =
    translates?.enabled == true && translates.showLanguageSelector != false
