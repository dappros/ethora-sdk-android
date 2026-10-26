package com.ethora.chat.core.xmpp

import com.ethora.chat.core.i18n.resolveMessageTranslation
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.MessageTranslation
import com.ethora.chat.core.models.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class MessageTranslationXmlTest {

    private fun groupchat(body: String, translate: String = "", translations: String = ""): String =
        """<message to="room@conference.x" type="groupchat" id="send-text-message-1">""" +
            """<data senderJID="ana@x" isSystemMessage="false"/>""" +
            "<body>$body</body>$translate$translations</message>"

    @Test
    fun `translate source round trips through an outgoing stanza`() {
        val xml = groupchat("Hola", translate = MessageTranslationXml.translateElement("es-US"))
        assertEquals("es-US", MessageTranslationXml.parseSource(xml))
    }

    @Test
    fun `a stanza without a translate element has no source`() {
        assertNull(MessageTranslationXml.parseSource(groupchat("Hi")))
    }

    @Test
    fun `source value is escaped for the attribute`() {
        val element = MessageTranslationXml.translateElement("en\"<x>")
        assertEquals("en\"<x>", MessageTranslationXml.parseSource(groupchat("hi", translate = element)))
    }

    @Test
    fun `translations parse from the entity-encoded JSON attribute`() {
        val json = """{"translates":[{"translatedText":"Bonjour","language":"fr","languageName":"Français"}]}"""
        val encoded = json.replace("\"", "&quot;")
        val xml = groupchat("Hello", translations = """<translations value="$encoded"/>""")
        assertEquals(
            mapOf("fr" to MessageTranslation("Bonjour", targetLanguage = "fr", languageName = "Français")),
            MessageTranslationXml.parseTranslations(xml)
        )
    }

    @Test
    fun `entries missing translatedText, language or languageName are dropped`() {
        val json = """{"translates":[
            {"translatedText":"Hola","language":"es","languageName":"Español"},
            {"language":"de","languageName":"Deutsch"},
            {"translatedText":"Ciao","languageName":"Italiano"},
            {"translatedText":"Hallo","language":"de"},
            {"translatedText":7,"language":"pt","languageName":"Português"}
        ]}""".replace("\n", "").replace("\"", "&quot;")
        val parsed = MessageTranslationXml.parseTranslations(
            groupchat("Hi", translations = """<translations value="$json"/>""")
        )
        assertEquals(listOf("es"), parsed!!.keys.toList())
    }

    @Test
    fun `malformed translations give null instead of throwing`() {
        val xml = groupchat("Hi", translations = """<translations value="{not json"/>""")
        assertNull(MessageTranslationXml.parseTranslations(xml))
    }

    @Test
    fun `translations element is not mistaken for a translate element`() {
        val xml = groupchat("Hi", translations = """<translations value="{}"/>""")
        assertNull(MessageTranslationXml.parseSource(xml))
    }

    @Test
    fun `resolver shows an attached translation only when the languages differ`() {
        val message = Message(
            id = "1",
            user = User(id = "ana"),
            date = Date(),
            body = "Hola",
            roomJid = "room@conference.x",
            langSource = "es",
            translations = mapOf("en" to MessageTranslation("Hello", targetLanguage = "en"))
        )
        val resolved = resolveMessageTranslation(message, "en-CA")
        assertTrue(resolved.needsTranslation)
        assertTrue(resolved.hasTranslation)
        assertEquals("Hello", resolved.displayText)
        assertEquals("Hola", resolved.originalText)

        val sameLanguage = resolveMessageTranslation(message.copy(langSource = "en-US"), "en-CA")
        assertFalse(sameLanguage.needsTranslation)
        assertEquals("Hola", sameLanguage.displayText)
    }
}
