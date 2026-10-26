package com.ethora.chat.core.e2ee

/**
 * Turning OMEMO stanzas into ordinary ones. Port of the rewrite in web
 * `src/e2ee/stanza.ts`: the decrypted SCE content replaces <encrypted>, so every
 * downstream handler (realtime, MAM, reactions, edits, history) sees the stanza an
 * unencrypted sender would have produced.
 */
object OmemoStanzas {
    private const val MAX_DEPTH = 4

    /** Finds the <message> that carries <encrypted>, through mucsub and MAM wrappers. */
    fun encryptedCarrier(stanza: XmlElement, depth: Int = 0): XmlElement? {
        if (depth > MAX_DEPTH) return null
        if (stanza.name == "message" && stanza.child("encrypted", OmemoManager.NS_OMEMO) != null) return stanza
        for (child in stanza.elements) {
            encryptedCarrier(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Returns the stanza text to process, or null when the stanza must be
     * dropped (session maintenance with nothing to show). Returns [xml] unchanged
     * when OMEMO is off or the stanza carries no encrypted payload.
     */
    suspend fun rewriteInbound(xml: String, domain: String): String? {
        if (!E2eeRuntime.isEnabled() || !xml.contains(OmemoManager.NS_OMEMO)) return xml
        val root = try {
            XmlParser.parse(xml)
        } catch (e: IllegalArgumentException) {
            return xml
        }
        val carrier = encryptedCarrier(root) ?: return xml
        val encrypted = carrier.child("encrypted", OmemoManager.NS_OMEMO) ?: return xml
        // No payload: a session-maintenance message, nothing to render
        if (encrypted.child("payload") == null) return null

        val from = carrier.attr("from").orEmpty()
        val roomJid = from.substringBefore('/')
        val sender = senderJidFromMuc(from, domain)
        val manager = E2eeRuntime.ready()
        val decrypted = if (sender != null && manager != null) manager.decrypt(carrier, sender, roomJid) else null

        // The fallback <body> and the OMEMO elements exist for clients that cannot
        // decrypt; drop them either way. <data> is kept: it is the sender's real
        // metadata, sent in the clear beside <encrypted>.
        carrier.removeChildren("encrypted", OmemoManager.NS_OMEMO)
        carrier.removeChildren("encryption", OmemoManager.NS_EME)
        carrier.removeChildren("body")

        val content = decrypted?.content
        if (!content.isNullOrEmpty()) {
            carrier.addAll(liftSealedMedia(XmlParser.parseFragments(content), carrier))
        } else {
            carrier.add(placeholderBody(decrypted?.error))
        }

        // Marks the message as having arrived encrypted, on both branches. The
        // mark is stored on <data>, which is cleartext and therefore readable.
        val data = carrier.child("data") ?: XmlElement("data").also { carrier.add(it) }
        data.attrs["omemoEncrypted"] = "true"
        return root.toXml()
    }

    /**
     * The real bare JID behind a MUC `from`: `room@conference/nick` -> `nick@domain`.
     * This works because the SDK always joins a room with the account's localpart as
     * the nick, so the room never has to disclose real JIDs.
     */
    fun senderJidFromMuc(from: String?, domain: String): String? {
        val nick = from.orEmpty().substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
        if (domain.isEmpty()) return null
        return "$nick@$domain".lowercase()
    }

    /**
     * A sealed media message has its keys inside the encrypted body. Move them
     * onto <data> as `e2eeKeys` (a JSON array) and turn the body into the
     * literal "media", exactly like an unencrypted media message. Returns the
     * fragments to append to the carrier.
     */
    private fun liftSealedMedia(fragments: List<Any>, carrier: XmlElement): List<Any> {
        val body = fragments.filterIsInstance<XmlElement>().firstOrNull { it.name == "body" } ?: return fragments
        val sealed = OmemoFileEnvelope.parseSealedMedia(body.text()) ?: return fragments
        val data = carrier.child("data") ?: XmlElement("data").also { carrier.add(it) }
        // Base64 keys contain no XML-special characters, so a comma list survives as an attribute
        data.attrs["e2eeKeys"] = sealed.keys.joinToString(",")
        return fragments.map { if (it === body) XmlElement("body").add("media") else it }
    }

    private fun placeholderBody(error: String?): XmlElement {
        val text = if (error == "omemo_not_encrypted_for_this_device") {
            "Encrypted for another device"
        } else {
            "Could not decrypt this message"
        }
        return XmlElement("body").add(text)
    }
}
