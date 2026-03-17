package com.ethora.chat.core.xmpp

import org.jivesoftware.smack.packet.Stanza

/**
 * XMPP Stanza wrapper
 */
data class XMPPStanza(
    val type: String,
    val from: String?,
    val to: String?,
    val id: String?,
    val body: String? = null,
    val xml: String? = null,
    val stanza: Stanza? = null
) {
    fun toXML(): String {
        return xml ?: stanza?.toXML()?.toString() ?: ""
    }
}
