package com.ethora.chat.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of web `src/i18n/strings.test.ts`: locale resolution, fallback, overrides, completeness. */
class ChatI18nTest {

    private val english = UiStringTables.byLanguage.getValue("en")()
    private val placeholder = Regex("\\{(\\w+)\\}")

    @Test
    fun `every built-in language has every English key`() {
        for (lang in ChatI18n.BUILTIN_LANGUAGES.filter { it != "en" }) {
            val table = UiStringTables.byLanguage.getValue(lang)()
            val missing = english.keys.filterNot { it in table }
            assertTrue("$lang is missing $missing", missing.isEmpty())
        }
    }

    @Test
    fun `no built-in value is blank`() {
        for (lang in ChatI18n.BUILTIN_LANGUAGES) {
            val empty = UiStringTables.byLanguage.getValue(lang)().filterValues { it.isBlank() }.keys
            assertTrue("$lang has blank values $empty", empty.isEmpty())
        }
    }

    @Test
    fun `translations keep the placeholders of the English source`() {
        for (lang in ChatI18n.BUILTIN_LANGUAGES.filter { it != "en" }) {
            val table = UiStringTables.byLanguage.getValue(lang)()
            val mismatches = english.keys.filter { it in table }.filter { key ->
                placeholders(english.getValue(key)) != placeholders(table.getValue(key))
            }
            assertTrue("$lang placeholder mismatch $mismatches", mismatches.isEmpty())
        }
    }

    @Test
    fun `keys are alphabetical in every table`() {
        for (lang in ChatI18n.BUILTIN_LANGUAGES) {
            val keys = UiStringTables.byLanguage.getValue(lang)().keys.toList()
            assertEquals("$lang keys out of order", keys.sorted(), keys)
        }
    }

    @Test
    fun `toBaseLanguage reduces a locale to its base`() {
        assertEquals("fr", ChatI18n.toBaseLanguage("fr-CA"))
        assertEquals("en", ChatI18n.toBaseLanguage("EN-us"))
        assertEquals("en", ChatI18n.toBaseLanguage(null))
        assertEquals("en", ChatI18n.toBaseLanguage(""))
    }

    @Test
    fun `a built-in language resolves its own captions`() {
        assertEquals("Enviar", ChatI18n.resolveStringTable("es")["action.send"])
    }

    @Test
    fun `an unknown language falls back to English`() {
        assertEquals("Send", ChatI18n.resolveStringTable("xx")["action.send"])
    }

    @Test
    fun `host overrides win over the built-in table and keep the rest`() {
        assertEquals("Go", ChatI18n.resolveStringTable("en", mapOf("action.send" to "Go"))["action.send"])
        val es = ChatI18n.resolveStringTable("es", mapOf("action.send" to "Custom"))
        assertEquals("Custom", es["action.send"])
        assertEquals("Cancelar", es["action.cancel"])
    }

    @Test
    fun `interpolate fills named placeholders and keeps unknown ones`() {
        assertEquals("3 online", ChatI18n.interpolate("{count} online", mapOf("count" to 3)))
        assertEquals("{count} online", ChatI18n.interpolate("{count} online", emptyMap()))
        assertEquals("Send", ChatI18n.interpolate("Send"))
    }

    @Test
    fun `translateKey resolves and interpolates in one call`() {
        assertEquals("5 en línea", ChatI18n.translateKey("presence.onlineCount", "es", null, mapOf("count" to 5)))
        assertEquals("not.a.real.key", ChatI18n.translateKey("not.a.real.key", "en"))
    }

    @Test
    fun `language options resolve to a built-in table`() {
        for (option in LANGUAGE_OPTIONS) {
            assertTrue(ChatI18n.toBaseLanguage(option.id) in ChatI18n.BUILTIN_LANGUAGES)
        }
    }

    private fun placeholders(text: String): List<String> =
        placeholder.findAll(text).map { it.groupValues[1] }.sorted().toList()
}
