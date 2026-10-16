package com.ethora.chat.core.xmpp

/**
 * Room-management stanzas, wire-compatible with the web SDK:
 *   - invite:          networking/xmpp/inviteRoomRequest.xmpp.ts
 *   - leave:           networking/xmpp/leaveTheRoom.xmpp.ts
 *   - members refresh: networking/xmpp/notifyMembersChanged.xmpp.ts
 *   - ethora-event:    networking/ethoraEvents.ts
 *
 * Builders are pure strings, parsers are pure functions over the raw XML,
 * so the shapes can be unit tested without a socket.
 */

const val MUC_USER_NS = "http://jabber.org/protocol/muc#user"
const val MEMBERS_REFRESH_NS = "ethora:chats:members-refresh"
const val ETHORA_EVENT_NS = "urn:ethora:events:1"
const val ETHORA_EVENT_USER_PROFILE = "user-profile-updated"
const val ETHORA_EVENT_CHAT_META = "chat-meta-updated"

/** Escapes a value for use inside a single-quoted XML attribute. */
internal fun xmlAttrEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("'", "&apos;")
    .replace("\"", "&quot;")

object RoomStanzas {

    /**
     * Presence to `{room}/{nick}` with type unavailable: leaves the MUC for
     * this session. Web leaveTheRoom.xmpp.ts.
     */
    fun leavePresence(roomJid: String, nick: String): String =
        "<presence to='${xmlAttrEscape(roomJid)}/${xmlAttrEscape(nick)}' type='unavailable'/>"

    /**
     * Invite a user into a MUC. `username` is the xmppUsername (`appId_userId`),
     * `host` the XMPP base domain (web CHAT_DOMAIN). Web inviteRoomRequest.xmpp.ts.
     */
    fun invite(roomJid: String, username: String, host: String, id: String): String =
        "<message to='${xmlAttrEscape(roomJid)}' id='${xmlAttrEscape(id)}'>" +
            "<x xmlns='$MUC_USER_NS'>" +
            "<invite to='${xmlAttrEscape(username)}@${xmlAttrEscape(host)}'/>" +
            "</x></message>"

    /**
     * Groupchat wake-up telling every occupant to re-read the room from the
     * REST API (the server is the source of truth for membership). Web
     * notifyMembersChanged.xmpp.ts.
     */
    fun membersRefresh(roomJid: String, nick: String, affiliation: String, id: String): String =
        "<message to='${xmlAttrEscape(roomJid)}' type='groupchat' id='${xmlAttrEscape(id)}'>" +
            "<members-refresh xmlns='$MEMBERS_REFRESH_NS'/>" +
            "<no-store xmlns='urn:xmpp:hints'/>" +
            "<x xmlns='$MUC_USER_NS'>" +
            "<item affiliation='${xmlAttrEscape(affiliation)}' nick='${xmlAttrEscape(nick)}'/>" +
            "</x></message>"
}

/** A parsed `<ethora-event>` pointer. Everything in it is untrusted until checked. */
data class EthoraEventHeadline(
    val type: String,
    val appId: String?,
    val xmppUsername: String?,
    val chatName: String?
)

object EthoraEventParser {
    private val MESSAGE_OPEN_TAG = Regex("<message\\b[^>]*>")
    private val EVENT_TAG = Regex("<ethora-event\\b[^>]*>")
    private val KEY_SHAPE = Regex("^[A-Za-z0-9_-]+$")
    private const val MAX_KEY_LENGTH = 128

    /**
     * The message that carries the `<ethora-event>`: the stanza itself, or the
     * inner message of a MUC-SUB pubsub wrapper. Null when the stanza carries
     * no headline with an ethora-event (the caller then routes it normally).
     */
    fun carrier(xml: String): String? {
        if (!xml.contains("<ethora-event")) return null
        val candidate = unwrapMucSubInnerMessage(xml)
        val tag = MESSAGE_OPEN_TAG.find(candidate)?.value ?: return null
        if (attr(tag, "type") != "headline") return null
        if (!EVENT_TAG.containsMatchIn(candidate)) return null
        return candidate
    }

    /** Parses the `<ethora-event xmlns="urn:ethora:events:1" .../>` of [carrierXml]. */
    fun parse(carrierXml: String): EthoraEventHeadline? {
        val tag = EVENT_TAG.find(carrierXml)?.value ?: return null
        if (attr(tag, "xmlns") != ETHORA_EVENT_NS) return null
        return EthoraEventHeadline(
            type = attr(tag, "type").orEmpty(),
            appId = attr(tag, "appId"),
            xmppUsername = attr(tag, "xmppUsername"),
            chatName = attr(tag, "chatName")
        )
    }

    /** The `from` of the carrier: a BARE jid on our own XMPP domain. */
    fun from(carrierXml: String): String =
        attr(MESSAGE_OPEN_TAG.find(carrierXml)?.value.orEmpty(), "from").orEmpty()

    /**
     * Web isTrustedEventSender: the sender must be a bare `local@domain`
     * (a user's stanza or a MUC occupant always carries a resource, so it
     * cannot pass), and the domain must be this session's XMPP domain.
     */
    fun isTrustedSender(from: String, ownDomain: String): Boolean {
        if (from.isEmpty() || from.contains('/')) return false
        val at = from.indexOf('@')
        if (at <= 0 || from.indexOf('@', at + 1) != -1) return false
        val local = from.substring(0, at).trim().lowercase()
        val domain = from.substring(at + 1).trim().lowercase()
        val own = ownDomain.trim().lowercase()
        return local.isNotEmpty() && domain.isNotEmpty() && own.isNotEmpty() && domain == own
    }

    /**
     * `<appId>_<id>` shape for an attribute that becomes a URL segment or a
     * store key: charset, length cap, both segments non-empty, and the
     * session's appId as prefix when the session has one.
     */
    fun isSafeKey(value: String?, appId: String): Boolean {
        if (value.isNullOrEmpty() || value.length > MAX_KEY_LENGTH) return false
        if (!KEY_SHAPE.matches(value) || !value.contains('_')) return false
        if (value.split('_').any { it.isEmpty() }) return false
        if (appId.isNotEmpty() && !value.startsWith("${appId}_")) return false
        return true
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}=[\"']([^\"']*)[\"']").find(tag)
            ?.groupValues?.get(1)
            ?.let { decodeXmlEntities(it) }
}
