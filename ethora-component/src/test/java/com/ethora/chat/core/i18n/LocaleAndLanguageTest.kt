package com.ethora.chat.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocaleAndLanguageTest {

    @Test
    fun `canonicalLocale normalises region case and rejects non-tags`() {
        assertEquals("en-CA", canonicalLocale("en-ca"))
        assertEquals("fr", canonicalLocale("FR"))
        assertEquals("pt-BR", canonicalLocale("pt_br"))
        assertNull(canonicalLocale("not a locale!"))
        assertNull(canonicalLocale(""))
        assertNull(canonicalLocale(null))
    }

    @Test
    fun `languageLabel uses the picker name, then the autonym`() {
        assertEquals("Français", languageLabel("fr-CA"))
        assertEquals("Português", languageLabel("pt-BR"))
        assertEquals("Deutsch", languageLabel("de"))
        assertEquals("xx-YY", languageLabel("xx-YY"))
    }

    @Test
    fun `languageChoices falls back to the built-in list when no valid tag is configured`() {
        assertEquals(LANGUAGE_OPTIONS, languageChoices(null))
        assertEquals(LANGUAGE_OPTIONS, languageChoices(listOf("!!!")))
        assertEquals(listOf("de-DE"), languageChoices(listOf("de-de")).map { it.id })
        assertEquals(listOf("de-DE", "it"), languageChoices(listOf("de-de", "it", "de-DE")).map { it.id })
    }
}
