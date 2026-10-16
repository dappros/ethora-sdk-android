package com.ethora.chat.core.i18n

import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.I18nConfig
import com.ethora.chat.core.config.TranslateMode
import com.ethora.chat.core.config.TranslationsConfig
import com.ethora.chat.core.networking.AccountService
import com.ethora.chat.core.store.ReaderLanguageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPolicyTest {

    @Test
    fun `forceType pins the host mode and hides the switcher`() {
        val pinned = TranslationsConfig(enabled = true, mode = TranslateMode.MANUAL, forceType = true)
        assertEquals(TranslateMode.MANUAL, resolveTranslateMode(pinned, TranslateMode.AUTO))
        assertFalse(canReaderChooseTranslateMode(pinned))
    }

    @Test
    fun `reader pick wins over the host default when not forced`() {
        val open = TranslationsConfig(enabled = true, mode = TranslateMode.MANUAL)
        assertEquals(TranslateMode.MANUAL, resolveTranslateMode(open, null))
        assertEquals(TranslateMode.AUTO, resolveTranslateMode(open, TranslateMode.AUTO))
        assertTrue(canReaderChooseTranslateMode(open))
    }

    @Test
    fun `outgoing text is tagged only when translation is on and not switched off`() {
        assertTrue(shouldTagOutgoingTranslateSource(true, null))
        assertTrue(shouldTagOutgoingTranslateSource(true, true))
        assertFalse(shouldTagOutgoingTranslateSource(true, false))
        assertFalse(shouldTagOutgoingTranslateSource(false, null))
        assertFalse(shouldTagOutgoingTranslateSource(null, null))
    }

    @Test
    fun `selector is shown only when enabled and not hidden by the host`() {
        assertTrue(shouldShowLanguageSelector(TranslationsConfig(enabled = true)))
        assertFalse(shouldShowLanguageSelector(TranslationsConfig(enabled = true, showLanguageSelector = false)))
        assertFalse(shouldShowLanguageSelector(TranslationsConfig(enabled = false)))
    }

    @Test
    fun `outgoing source uses the reader's chat language, defaulting to English`() {
        ReaderLanguageStore.clear()
        val config = ChatConfig(translates = TranslationsConfig(enabled = true))
        assertEquals("en", ReaderLanguageStore.outgoingTranslateSource(config))
        ReaderLanguageStore.setChatLanguage("fr-CA")
        assertEquals("fr-CA", ReaderLanguageStore.outgoingTranslateSource(config))
        ReaderLanguageStore.setTranslateSendEnabled(false)
        assertNull(ReaderLanguageStore.outgoingTranslateSource(config))
        ReaderLanguageStore.clear()
    }

    @Test
    fun `profile languages apply only when the Language card is enabled`() {
        ReaderLanguageStore.clear()
        ReaderLanguageStore.applyProfile("fr-ca", null, languagesEnabled = false)
        assertNull(ReaderLanguageStore.uiLanguage.value)

        ReaderLanguageStore.applyProfile("fr-ca", null, languagesEnabled = true)
        assertEquals("fr-CA", ReaderLanguageStore.uiLanguage.value)
        assertEquals("fr-CA", ReaderLanguageStore.chatLanguage.value)
        ReaderLanguageStore.clear()
    }

    @Test
    fun `ui locale prefers the host locale, then app language, then chat language`() {
        assertEquals(
            "de",
            resolveUiLocale(ChatConfig(i18n = I18nConfig(locale = "de")), "fr-CA", "es")
        )
        assertEquals("fr-CA", resolveUiLocale(null, "fr-CA", "es"))
        assertEquals("es", resolveUiLocale(null, null, "es"))
        assertNull(resolveUiLocale(null, null, null))
    }
}

class AccountServiceTest {

    @Test
    fun `api root drops the version suffix so each call names its own version`() {
        assertEquals("https://api.chat.ethora.com", AccountService.apiRoot("https://api.chat.ethora.com/v1"))
        assertEquals("https://api.chat.ethora.com", AccountService.apiRoot("https://api.chat.ethora.com/v2/"))
        assertEquals("https://api.chat.ethora.com", AccountService.apiRoot("https://api.chat.ethora.com"))
    }

    @Test
    fun `server reason is read from message or error when the body is JSON`() {
        assertEquals("Wrong password", AccountService.serverMessageOf("""{"message":"Wrong password"}"""))
        assertEquals("denied", AccountService.serverMessageOf("""{"error":"denied"}"""))
        assertNull(AccountService.serverMessageOf("<html>502</html>"))
        assertNull(AccountService.serverMessageOf(""))
    }
}
