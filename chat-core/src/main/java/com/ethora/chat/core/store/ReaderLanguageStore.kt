package com.ethora.chat.core.store

import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.TranslateMode
import com.ethora.chat.core.i18n.canonicalLocale
import com.ethora.chat.core.i18n.shouldTagOutgoingTranslateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The reader's language choices, as the web component keeps them in `chatSettingStore`.
 *
 *  - [chatLanguage] (web `langSource`): language messages are translated into, and the language
 *    declared on the reader's own outgoing text.
 *  - [uiLanguage] (account `appLanguage`): language of the SDK's own captions.
 *  - [translateMode] and [translateSendEnabled]: the reader's picks in the language selector.
 *
 * Kept in memory. The account profile is the durable copy for the two languages, applied when a
 * session starts (see `AccountService.applyProfileLanguages`).
 */
object ReaderLanguageStore {
    private val _chatLanguage = MutableStateFlow<String?>(null)
    val chatLanguage: StateFlow<String?> = _chatLanguage.asStateFlow()

    private val _uiLanguage = MutableStateFlow<String?>(null)
    val uiLanguage: StateFlow<String?> = _uiLanguage.asStateFlow()

    private val _translateMode = MutableStateFlow<TranslateMode?>(null)
    val translateMode: StateFlow<TranslateMode?> = _translateMode.asStateFlow()

    private val _translateSendEnabled = MutableStateFlow<Boolean?>(null)
    val translateSendEnabled: StateFlow<Boolean?> = _translateSendEnabled.asStateFlow()

    fun setChatLanguage(locale: String?) {
        _chatLanguage.value = locale?.takeIf { it.isNotBlank() }
    }

    fun setUiLanguage(locale: String?) {
        _uiLanguage.value = locale?.takeIf { it.isNotBlank() }
    }

    fun setTranslateMode(mode: TranslateMode?) {
        _translateMode.value = mode
    }

    fun setTranslateSendEnabled(enabled: Boolean?) {
        _translateSendEnabled.value = enabled
    }

    /**
     * Apply the profile's languages at session start. Only when the Language card is enabled.
     * An unset chat language follows the app language, as the backend does.
     */
    fun applyProfile(appLanguage: String?, chatLanguage: String?, languagesEnabled: Boolean) {
        if (!languagesEnabled) return
        val app = canonicalLocale(appLanguage)
        if (app != null) _uiLanguage.value = app
        val chat = canonicalLocale(chatLanguage) ?: app
        if (chat != null) _chatLanguage.value = chat
    }

    /**
     * The `<translate source>` value for an outgoing text, or null for a plain send. Web
     * `useSendMessage`: tagged when translation is on and the reader has not switched sending off;
     * the source is the reader's chat language, falling back to English.
     */
    fun outgoingTranslateSource(config: ChatConfig?): String? {
        val tag = shouldTagOutgoingTranslateSource(
            translatesEnabled = config?.translates?.enabled,
            translateSendEnabled = _translateSendEnabled.value
        )
        if (!tag) return null
        return _chatLanguage.value ?: DEFAULT_SOURCE_LANGUAGE
    }

    private const val DEFAULT_SOURCE_LANGUAGE = "en"

    /** Forget the reader's picks (logout). */
    fun clear() {
        _chatLanguage.value = null
        _uiLanguage.value = null
        _translateMode.value = null
        _translateSendEnabled.value = null
    }
}
