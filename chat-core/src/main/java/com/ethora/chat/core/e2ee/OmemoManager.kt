package com.ethora.chat.core.e2ee

import com.ethora.chat.core.e2ee.OmemoCrypto.toBase64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Storage for OMEMO keys, sessions, trust records and decrypted messages (JSON values). */
interface OmemoStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Volatile store, for tests and hosts that do not want keys on disk. */
class InMemoryOmemoStore : OmemoStore {
    private val data = HashMap<String, String>()
    override fun get(key: String): String? = synchronized(data) { data[key] }
    override fun put(key: String, value: String) = synchronized(data) { data[key] = value }
    override fun remove(key: String) {
        synchronized(data) { data.remove(key) }
    }
}

/**
 * What OMEMO needs from the XMPP connection. `request` sends an IQ and
 * suspends until its result; an error reply throws [OmemoIqException] with the
 * stanza-error condition (for example `item-not-found`).
 */
interface OmemoTransport {
    /** Bare JID of this account, lowercased. */
    val ownJid: String
    suspend fun request(iq: XmlElement): XmlElement
    fun send(stanza: XmlElement)
}

class OmemoIqException(val condition: String, message: String = condition) : RuntimeException(message)

/** Failure to encrypt for a room at all, e.g. no member has published a device. */
class OmemoException(message: String) : RuntimeException(message)

/** Blind Trust Before Verification: new devices are trusted until one is verified. */
object OmemoTrust {
    const val BLIND = "blind"
    const val VERIFIED = "verified"
    const val UNTRUSTED = "untrusted"
    const val OWN = "own"
}

/** A decrypted SCE envelope: the serialized content children (or null for session-only messages). */
class OmemoDecrypted(val content: String?, val trust: String, val error: String? = null)

/** One device of a user, as shown in the UI. */
class OmemoDeviceInfo(val id: Long, val fingerprint: String, val trust: String)

/**
 * OMEMO 2 for one account on this device, across the MUC rooms it is in.
 * Port of web `src/e2ee/omemo.ts`. The wire format is the same; differences
 * are called out where they arise.
 *
 * Every chat here is a MUC room, so a message is encrypted for the devices of
 * EVERY member, and the author of an incoming stanza is a room nick that
 * resolves to `nick@domain`. Only <body> is inside the SCE envelope; the
 * custom <data> element stays in the clear beside <encrypted> because the
 * server's push pipeline reads it.
 */
class OmemoManager private constructor(
    private val transport: OmemoTransport,
    private val store: OmemoStore,
    private var keys: OwnKeys,
    private val scope: CoroutineScope
) {
    /** Keys and per-account state, persisted as one JSON value under "keys". */
    internal class OwnKeys(
        var deviceId: Long,
        var identitySeed: ByteArray,
        var identityPub: ByteArray,
        var spkId: Long,
        var spkPriv: ByteArray,
        var spkPub: ByteArray,
        var spkSignature: ByteArray,
        var prekeys: MutableMap<String, OmemoCrypto.KeyPair>,
        var nextPrekeyId: Long
    )

    private class DeviceRecord(var ik: ByteArray, var trust: String)

    private class CachedDecrypted(val content: String?, val trust: String, val error: String?)

    private val gson = Gson()
    private val mutex = Mutex()
    private val deviceLists = HashMap<String, Pair<List<XmlElement>, Long>>()

    val jid: String = transport.ownJid
    val deviceId: Long get() = keys.deviceId
    val fingerprint: String get() = formatFingerprint(keys.identityPub)

    private val identity: OmemoRatchet.Identity
        get() = OmemoRatchet.Identity(keys.identitySeed, keys.identityPub)

    companion object {
        const val NS_OMEMO = "urn:xmpp:omemo:2"
        const val NS_SCE = "urn:xmpp:sce:1"
        const val NS_PUBSUB = "http://jabber.org/protocol/pubsub"
        const val NS_PUBSUB_OWNER = "http://jabber.org/protocol/pubsub#owner"
        const val NS_EME = "urn:xmpp:eme:0"
        const val NS_HINTS = "urn:xmpp:hints"
        const val NS_CARBONS = "urn:xmpp:carbons:2"
        const val NS_SID = "urn:xmpp:sid:0"
        const val NODE_DEVICES = "urn:xmpp:omemo:2:devices"
        const val NODE_BUNDLES = "urn:xmpp:omemo:2:bundles"
        const val PREKEY_COUNT = 100
        const val DEVICE_LIST_TTL_MS = 30_000L
        private const val DEVICE_LABEL = "Android"

        /**
         * The cleartext <body> of an encrypted stanza. Written once by the
         * sender, so it cannot be localised for the reader. It is what lands on
         * a lock screen, so keep it short and neutral.
         */
        const val FALLBACK_BODY = "Encrypted message"

        /** Identity key fingerprint as shown to users: hex in groups of 8. */
        fun formatFingerprint(ik: ByteArray): String = OmemoCrypto.toHex(ik).chunked(8).joinToString(" ")

        /** Loads (or creates) this device's keys, publishes them and announces the device. */
        suspend fun create(
            transport: OmemoTransport,
            store: OmemoStore,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ): OmemoManager {
            val existing = store.get("keys")?.let { Gson().fromJson(it, OwnKeys::class.java) }
            val keys = existing ?: generateOwnKeys().also { store.put("keys", Gson().toJson(it)) }
            val manager = OmemoManager(transport, store, keys, scope)
            manager.publishBundle()
            manager.announceDevice()
            return manager
        }

        private fun generateOwnKeys(): OwnKeys {
            val identity = OmemoCrypto.generateIdentity()
            val spk = OmemoCrypto.generateKeyPair()
            val prekeys = LinkedHashMap<String, OmemoCrypto.KeyPair>()
            for (id in 1..PREKEY_COUNT) prekeys[id.toString()] = OmemoCrypto.generateKeyPair()
            return OwnKeys(
                deviceId = OmemoCrypto.randomInt(1, Int.MAX_VALUE).toLong(),
                identitySeed = identity.seed,
                identityPub = identity.pub,
                spkId = 1,
                spkPriv = spk.priv,
                spkPub = spk.pub,
                spkSignature = OmemoCrypto.ed25519Sign(identity.seed, spk.pub),
                prekeys = prekeys,
                nextPrekeyId = PREKEY_COUNT + 1L
            )
        }
    }

    // -- helpers ----------------------------------------------------------

    private fun bare(jid: String): String = jid.substringBefore('/').lowercase()

    private fun sessionKey(jid: String, id: Long) = "session:$jid:$id"
    private fun trustKey(jid: String) = "trust:$jid"
    private fun messageKey(roomJid: String, id: String) = "message:${bare(roomJid)}:$id"

    private fun saveKeys() = store.put("keys", gson.toJson(keys))

    private fun loadSession(jid: String, id: Long): OmemoRatchet.Session? =
        store.get(sessionKey(jid, id))?.let { gson.fromJson(it, OmemoRatchet.Session::class.java) }

    private fun saveSession(jid: String, id: Long, session: OmemoRatchet.Session) =
        store.put(sessionKey(jid, id), gson.toJson(session))

    private fun loadTrust(jid: String): MutableMap<Long, DeviceRecord> {
        val json = store.get(trustKey(jid)) ?: return HashMap()
        val type = object : TypeToken<LinkedHashMap<String, DeviceRecord>>() {}.type
        val raw: LinkedHashMap<String, DeviceRecord> = gson.fromJson(json, type)
        return raw.mapKeys { it.key.toLong() }.toMutableMap()
    }

    private fun saveTrust(jid: String, records: Map<Long, DeviceRecord>) =
        store.put(trustKey(jid), gson.toJson(records.mapKeys { it.key.toString() }))

    private fun formField(name: String, value: String, type: String? = null): XmlElement {
        val attrs = linkedMapOf("var" to name)
        if (type != null) attrs["type"] = type
        return XmlElement("field", attrs).add(XmlElement("value").add(value))
    }

    private fun isPreconditionNotMet(e: OmemoIqException) =
        e.condition == "precondition-not-met" || e.condition == "conflict"

    // -- PEP ----------------------------------------------------------------

    /**
     * Publishes, and repairs the node if its configuration blocks us. A single
     * server-side change to a PEP node configuration would otherwise lock every
     * account out of publishing its keys for good; reconfiguring is tried first
     * because it keeps the items, deleting the node is the last resort.
     */
    private suspend fun publishWithRecovery(node: String, itemId: String, payload: XmlElement, maxItems: String?) {
        try {
            publish(node, itemId, payload, maxItems)
            return
        } catch (e: OmemoIqException) {
            if (!isPreconditionNotMet(e)) throw e
        }
        try {
            configureNode(node, maxItems)
            publish(node, itemId, payload, maxItems)
            return
        } catch (e: OmemoIqException) {
            if (!isPreconditionNotMet(e)) throw e
        }
        deleteNode(node)
        publish(node, itemId, payload, maxItems)
    }

    private suspend fun configureNode(node: String, maxItems: String?) {
        val fields = mutableListOf(
            formField("FORM_TYPE", "http://jabber.org/protocol/pubsub#node_config", "hidden"),
            formField("pubsub#access_model", "open"),
            formField("pubsub#persist_items", "true")
        )
        if (maxItems != null) fields += formField("pubsub#max_items", maxItems)
        val form = XmlElement("x", linkedMapOf("xmlns" to "jabber:x:data", "type" to "submit")).addAll(fields)
        val iq = XmlElement("iq", linkedMapOf("type" to "set")).add(
            XmlElement("pubsub", linkedMapOf("xmlns" to NS_PUBSUB_OWNER)).add(
                XmlElement("configure", linkedMapOf("node" to node)).add(form)
            )
        )
        transport.request(iq)
    }

    private suspend fun deleteNode(node: String) {
        transport.request(
            XmlElement("iq", linkedMapOf("type" to "set")).add(
                XmlElement("pubsub", linkedMapOf("xmlns" to NS_PUBSUB_OWNER)).add(
                    XmlElement("delete", linkedMapOf("node" to node))
                )
            )
        )
    }

    private suspend fun publish(node: String, itemId: String, payload: XmlElement, maxItems: String?) {
        val fields = mutableListOf(
            formField("FORM_TYPE", "http://jabber.org/protocol/pubsub#publish-options", "hidden"),
            formField("pubsub#access_model", "open")
        )
        if (maxItems != null) fields += formField("pubsub#max_items", maxItems)
        val options = XmlElement("publish-options").add(
            XmlElement("x", linkedMapOf("xmlns" to "jabber:x:data", "type" to "submit")).addAll(fields)
        )
        val publishEl = XmlElement("publish", linkedMapOf("node" to node))
            .add(XmlElement("item", linkedMapOf("id" to itemId)).add(payload))
        transport.request(
            XmlElement("iq", linkedMapOf("type" to "set")).add(
                XmlElement("pubsub", linkedMapOf("xmlns" to NS_PUBSUB)).add(publishEl).add(options)
            )
        )
    }

    private suspend fun fetchItems(jid: String, node: String, itemId: String? = null): List<XmlElement> {
        val itemsEl = XmlElement("items", linkedMapOf("node" to node))
        if (itemId != null) itemsEl.add(XmlElement("item", linkedMapOf("id" to itemId)))
        return try {
            val res = transport.request(
                XmlElement("iq", linkedMapOf("type" to "get", "to" to jid)).add(
                    XmlElement("pubsub", linkedMapOf("xmlns" to NS_PUBSUB)).add(itemsEl)
                )
            )
            res.child("pubsub", NS_PUBSUB)?.child("items")?.children("item") ?: emptyList()
        } catch (e: OmemoIqException) {
            if (e.condition == "item-not-found") emptyList() else throw e
        }
    }

    private suspend fun fetchDeviceListElements(jid: String): List<XmlElement> {
        val items = fetchItems(jid, NODE_DEVICES)
        val list = items.firstOrNull { it.attr("id") == "current" } ?: items.firstOrNull()
        return (list?.child("devices", NS_OMEMO)?.children("device") ?: emptyList()).filter { d ->
            val id = d.attr("id")?.toLongOrNull()
            id != null && id > 0 && id <= 0xffffffffL
        }
    }

    private suspend fun deviceIds(jid: String, fresh: Boolean = false): List<Long> {
        val cached = deviceLists[jid]
        if (cached != null && !fresh && System.currentTimeMillis() - cached.second < DEVICE_LIST_TTL_MS) {
            return cached.first.mapNotNull { it.attr("id")?.toLongOrNull() }
        }
        val elements = fetchDeviceListElements(jid)
        deviceLists[jid] = elements to System.currentTimeMillis()
        return elements.mapNotNull { it.attr("id")?.toLongOrNull() }
    }

    /** Adds this device to our published device list. */
    private suspend fun announceDevice() {
        val devices = fetchDeviceListElements(jid)
        if (devices.any { it.attr("id")?.toLongOrNull() == deviceId }) return
        val list = XmlElement("devices", linkedMapOf("xmlns" to NS_OMEMO))
        for (d in devices) {
            val attrs = linkedMapOf("id" to (d.attr("id") ?: continue))
            d.attr("label")?.let { attrs["label"] = it }
            list.add(XmlElement("device", attrs))
        }
        list.add(XmlElement("device", linkedMapOf("id" to deviceId.toString(), "label" to DEVICE_LABEL)))
        publishWithRecovery(NODE_DEVICES, "current", list, null)
        deviceLists.remove(jid)
    }

    private suspend fun publishBundle() {
        val bundle = XmlElement("bundle", linkedMapOf("xmlns" to NS_OMEMO))
            .add(XmlElement("spk", linkedMapOf("id" to keys.spkId.toString())).add(toBase64(keys.spkPub)))
            .add(XmlElement("spks").add(toBase64(keys.spkSignature)))
            .add(XmlElement("ik").add(toBase64(keys.identityPub)))
            .add(
                XmlElement("prekeys").addAll(
                    keys.prekeys.entries.map { (id, pair) ->
                        XmlElement("pk", linkedMapOf("id" to id)).add(toBase64(pair.pub))
                    }
                )
            )
        publishWithRecovery(NODE_BUNDLES, deviceId.toString(), bundle, "max")
    }

    private suspend fun fetchBundle(jid: String, id: Long): OmemoRatchet.Bundle {
        val items = fetchItems(jid, NODE_BUNDLES, id.toString())
        val el = items.firstOrNull { it.attr("id") == id.toString() }?.child("bundle", NS_OMEMO)
        val spk = el?.child("spk")
        val prekeys = el?.child("prekeys")?.children("pk") ?: emptyList()
        if (el == null || spk == null || prekeys.isEmpty()) {
            throw IllegalStateException("No usable bundle for $jid/$id")
        }
        val pk = prekeys[OmemoCrypto.randomInt(0, prekeys.size - 1)]
        return OmemoRatchet.Bundle(
            ik = OmemoCrypto.fromBase64(el.child("ik")?.text() ?: ""),
            spkId = spk.attr("id")?.toLong() ?: throw IllegalStateException("Bundle without spk id"),
            spk = OmemoCrypto.fromBase64(spk.text()),
            spkSignature = OmemoCrypto.fromBase64(el.child("spks")?.text() ?: ""),
            pkId = pk.attr("id")?.toLong() ?: throw IllegalStateException("Bundle without pk id"),
            pk = OmemoCrypto.fromBase64(pk.text())
        )
    }

    // -- Trust ----------------------------------------------------------------

    /** Records the device's identity key on first sight and returns its trust. */
    private fun trustOf(jid: String, id: Long, ik: ByteArray): String {
        val records = loadTrust(jid)
        val known = records[id]
        if (known != null && OmemoCrypto.bytesEqual(known.ik, ik)) return known.trust
        // A new device, or a known device ID with a different key: never trusted blindly
        val hasVerified = records.values.any { it.trust == OmemoTrust.VERIFIED }
        val trust = if (known != null || hasVerified) OmemoTrust.UNTRUSTED else OmemoTrust.BLIND
        records[id] = DeviceRecord(ik, trust)
        saveTrust(jid, records)
        return trust
    }

    /** Devices of [jid] (fetching unknown bundles); includes this device for our own JID. */
    suspend fun devices(jid: String): List<OmemoDeviceInfo> = mutex.withLock {
        val target = bare(jid)
        val result = ArrayList<OmemoDeviceInfo>()
        for (id in deviceIds(target, fresh = true)) {
            if (target == this.jid && id == deviceId) {
                result += OmemoDeviceInfo(id, fingerprint, OmemoTrust.OWN)
                continue
            }
            try {
                val known = loadTrust(target)[id]
                val ik = known?.ik ?: fetchBundle(target, id).ik
                result += OmemoDeviceInfo(id, formatFingerprint(ik), trustOf(target, id, ik))
            } catch (e: Exception) {
                // A device without a usable bundle is skipped, as on web
            }
        }
        result
    }

    /** Sets a device's trust. Only `verified`, `blind` and `untrusted` are meaningful here. */
    suspend fun setTrust(jid: String, id: Long, trust: String) = mutex.withLock {
        val target = bare(jid)
        val records = loadTrust(target)
        val record = records[id] ?: throw IllegalArgumentException("Unknown device")
        record.trust = trust
        saveTrust(target, records)
    }

    // -- Encryption -----------------------------------------------------------

    /** Encrypts [keyMaterial] for every usable device of [jid], except this one. */
    private suspend fun encryptKey(jid: String, keyMaterial: ByteArray): List<XmlElement> {
        val elements = ArrayList<XmlElement>()
        for (id in deviceIds(jid)) {
            if (jid == this.jid && id == deviceId) continue
            try {
                var session = loadSession(jid, id)
                if (session == null) {
                    val bundle = fetchBundle(jid, id)
                    if (trustOf(jid, id, bundle.ik) == OmemoTrust.UNTRUSTED) continue
                    session = OmemoRatchet.initiateSession(identity, bundle)
                } else if (trustOf(jid, id, session.peerIk) == OmemoTrust.UNTRUSTED) {
                    continue
                }
                val (next, data) = OmemoRatchet.ratchetEncrypt(session, keyMaterial)
                saveSession(jid, id, next)
                // Until the device answers, every message repeats the key exchange
                val kex = next.pendingKex
                val encoded = if (kex != null) {
                    OmemoProtobuf.encodeKeyExchange(
                        OmemoProtobuf.KeyExchange(kex.pkId, kex.spkId, keys.identityPub, kex.ek, data)
                    )
                } else {
                    data
                }
                val attrs = linkedMapOf("rid" to id.toString())
                if (kex != null) attrs["kex"] = "true"
                elements += XmlElement("key", attrs).add(toBase64(encoded))
            } catch (e: Exception) {
                // Skip this device; the rest of the room still gets the message
            }
        }
        return elements
    }

    /**
     * Builds the encrypted groupchat stanza for [roomJid].
     *
     * [content] goes inside the SCE envelope and is what OMEMO protects (today
     * the <body> alone). [cleartext] is appended beside <encrypted> untouched:
     * it carries <data>, which the server reads. [members] are the bare JIDs of
     * everyone in the room; ours is added automatically. [id] is the id the
     * plaintext stanza would have used, because the optimistic UI matches the echo by it.
     */
    suspend fun encryptGroupMessage(
        roomJid: String,
        members: List<String>,
        content: List<XmlElement>,
        id: String,
        cleartext: List<XmlElement> = emptyList()
    ): XmlElement = mutex.withLock {
        val room = bare(roomJid)
        val recipients = (members.map { bare(it) } + jid).filter { it.isNotEmpty() }.distinct()

        val envelope = XmlElement("envelope", linkedMapOf("xmlns" to NS_SCE))
            .add(XmlElement("content").addAll(content))
            .add(XmlElement("rpad").add(toBase64(OmemoCrypto.randomBytes(OmemoCrypto.randomInt(0, 48)))))
            .add(XmlElement("time", linkedMapOf("stamp" to Instant.now().toString())))
            // In a MUC the envelope is addressed to the room, and `from` is our real JID
            .add(XmlElement("to", linkedMapOf("jid" to room)))
            .add(XmlElement("from", linkedMapOf("jid" to jid)))
        val payload = OmemoRatchet.encryptPayload(envelope.toXml().toByteArray(Charsets.UTF_8))

        val blocks = ArrayList<XmlElement>()
        val unreachable = ArrayList<String>()
        for (member in recipients) {
            var keys = encryptKey(member, payload.keyMaterial)
            if (keys.isEmpty() && member != jid) {
                // The member may have just added a device
                deviceLists.remove(member)
                keys = encryptKey(member, payload.keyMaterial)
            }
            if (keys.isNotEmpty()) blocks += XmlElement("keys", linkedMapOf("jid" to member)).addAll(keys)
            else if (member != jid) unreachable += member
        }
        if (blocks.isEmpty()) {
            // Encrypting to nobody would be worse than not encrypting at all
            throw OmemoException(
                "omemo_no_recipients: no published OMEMO device for " +
                    (unreachable.joinToString(", ").ifEmpty { "any member" }) +
                    " - they have to open the chat with encryption enabled at least once"
            )
        }

        // The room echoes our own message back, but we never encrypt to this
        // device, so cache the plaintext under the id the echo will carry.
        store.put(
            messageKey(room, id),
            gson.toJson(CachedDecrypted(content.joinToString("") { it.toXml() }, OmemoTrust.OWN, null))
        )

        XmlElement("message", linkedMapOf("type" to "groupchat", "to" to room, "id" to id))
            .add(
                XmlElement("encrypted", linkedMapOf("xmlns" to NS_OMEMO))
                    .add(XmlElement("header", linkedMapOf("sid" to deviceId.toString())).addAll(blocks))
                    .add(XmlElement("payload").add(toBase64(payload.payload)))
            )
            .add(XmlElement("encryption", linkedMapOf("xmlns" to NS_EME, "namespace" to NS_OMEMO, "name" to "OMEMO")))
            .add(XmlElement("store", linkedMapOf("xmlns" to NS_HINTS)))
            .add(XmlElement("origin-id", linkedMapOf("xmlns" to NS_SID, "id" to id)))
            .add(XmlElement("body").add(FALLBACK_BODY))
            .addAll(cleartext)
    }

    /** Empty OMEMO message that completes a session the peer started; goes straight to that device. */
    private suspend fun sendEmpty(jid: String, id: Long) {
        val session = loadSession(jid, id) ?: return
        val (next, data) = OmemoRatchet.ratchetEncrypt(session, ByteArray(32))
        saveSession(jid, id, next)
        transport.send(
            XmlElement("message", linkedMapOf("type" to "chat", "to" to jid, "id" to "omemo-empty-${System.currentTimeMillis().toString(36)}"))
                .add(
                    XmlElement("encrypted", linkedMapOf("xmlns" to NS_OMEMO)).add(
                        XmlElement("header", linkedMapOf("sid" to deviceId.toString())).add(
                            XmlElement("keys", linkedMapOf("jid" to jid)).add(
                                XmlElement("key", linkedMapOf("rid" to id.toString())).add(toBase64(data))
                            )
                        )
                    )
                )
                .add(XmlElement("no-permanent-store", linkedMapOf("xmlns" to NS_HINTS)))
                .add(XmlElement("no-copy", linkedMapOf("xmlns" to NS_HINTS)))
                .add(XmlElement("private", linkedMapOf("xmlns" to NS_CARBONS)))
        )
    }

    // -- Decryption -------------------------------------------------------------

    /**
     * Decrypts the OMEMO payload of [carrier] (the <message> holding <encrypted>).
     * [sender] is the real bare JID of the author, resolved from the room nick
     * by the caller, and [roomJid] the room it arrived in.
     */
    suspend fun decrypt(carrier: XmlElement, sender: String, roomJid: String): OmemoDecrypted = mutex.withLock {
        val encrypted = carrier.child("encrypted", NS_OMEMO)
            ?: return@withLock OmemoDecrypted(null, OmemoTrust.UNTRUSTED, "omemo_not_encrypted")
        val from = bare(sender)
        val room = bare(roomJid)
        val cacheId = carrier.attr("id")?.let { messageKey(room, it) }
        if (cacheId != null) {
            store.get(cacheId)?.let {
                val cached = gson.fromJson(it, CachedDecrypted::class.java)
                return@withLock OmemoDecrypted(cached.content, cached.trust, cached.error)
            }
        }
        // Empty messages are not shown, so their failures do not matter either
        val empty = encrypted.child("payload") == null
        try {
            val result = decryptUncached(encrypted, from, room)
            if (empty) return@withLock OmemoDecrypted(null, result.trust)
            // Also makes repeated deliveries (live, MUC-SUB and archive) idempotent
            if (cacheId != null && result.error == null) {
                store.put(cacheId, gson.toJson(CachedDecrypted(result.content, result.trust, null)))
            }
            result
        } catch (e: Exception) {
            if (empty) OmemoDecrypted(null, OmemoTrust.UNTRUSTED)
            else OmemoDecrypted(null, OmemoTrust.UNTRUSTED, "omemo_decrypt_failed")
        }
    }

    private suspend fun decryptUncached(encrypted: XmlElement, sender: String, roomJid: String): OmemoDecrypted {
        val header = encrypted.child("header") ?: throw IllegalArgumentException("Invalid OMEMO header")
        val sid = header.attr("sid")?.toLongOrNull() ?: throw IllegalArgumentException("Invalid OMEMO header")
        if (sender == jid && sid == deviceId) {
            return OmemoDecrypted(null, OmemoTrust.OWN, "omemo_own_message_not_cached")
        }

        val keyEl = header.children("keys")
            .filter { bare(it.attr("jid") ?: "") == jid }
            .flatMap { it.children("key") }
            .firstOrNull { it.attr("rid")?.toLongOrNull() == deviceId }
            ?: return OmemoDecrypted(null, OmemoTrust.UNTRUSTED, "omemo_not_encrypted_for_this_device")

        val data = OmemoCrypto.fromBase64(keyEl.text())
        val existing = loadSession(sender, sid)
        val session: OmemoRatchet.Session
        val keyMaterial: ByteArray
        var usedPrekey: Long? = null

        if (keyEl.attr("kex") == "true" || keyEl.attr("kex") == "1") {
            val kex = OmemoProtobuf.decodeKeyExchange(data)
            val existingEk = existing?.kexEk
            if (existing != null && existingEk != null &&
                OmemoCrypto.bytesEqual(existingEk, kex.ek) && OmemoCrypto.bytesEqual(existing.peerIk, kex.ik)
            ) {
                // Repeated key exchange of a session we already accepted
                val (s, km) = OmemoRatchet.ratchetDecrypt(existing, kex.message)
                session = s
                keyMaterial = km
            } else {
                val pk = keys.prekeys[kex.pkId.toString()]
                if (kex.spkId != keys.spkId || pk == null) throw IllegalArgumentException("Unknown prekey")
                val accepted = OmemoRatchet.acceptSession(
                    identity,
                    OmemoCrypto.KeyPair(keys.spkPriv, keys.spkPub),
                    pk,
                    kex.ik,
                    kex.ek
                )
                val (s, km) = OmemoRatchet.ratchetDecrypt(accepted, kex.message)
                session = s
                keyMaterial = km
                usedPrekey = kex.pkId
            }
        } else {
            if (existing == null) throw IllegalStateException("No session with the sender device")
            val (s, km) = OmemoRatchet.ratchetDecrypt(existing, data)
            session = s
            keyMaterial = km
        }
        // The device answered: no need to repeat our key exchange
        session.pendingKex = null

        val payload = encrypted.child("payload")?.text()
        val content = payload?.let {
            openEnvelope(OmemoRatchet.decryptPayload(keyMaterial, OmemoCrypto.fromBase64(it)), sender, roomJid)
        }
        val trust = trustOf(sender, sid, session.peerIk)
        saveSession(sender, sid, session)

        if (usedPrekey != null) {
            replacePrekey(usedPrekey)
            // After this task: republish the bundle and confirm the new session
            scope.launch {
                try {
                    mutex.withLock {
                        publishBundle()
                        sendEmpty(sender, sid)
                    }
                } catch (e: Exception) {
                    // Non-fatal: the peer keeps repeating its key exchange until it gets through
                }
            }
        }
        return OmemoDecrypted(content, trust)
    }

    /**
     * Checks the SCE envelope (XEP-0420) and returns its content as XML. The
     * from/to check stops one member replaying another member's encrypted
     * message under their own nick.
     */
    private fun openEnvelope(bytes: ByteArray, sender: String, roomJid: String): String {
        val envelope = XmlParser.parse(String(bytes, Charsets.UTF_8))
        if (envelope.name != "envelope" || envelope.namespace != NS_SCE) {
            throw IllegalArgumentException("Not an SCE envelope")
        }
        val from = envelope.child("from")?.attr("jid")
        val to = envelope.child("to")?.attr("jid")
        if (from == null || bare(from) != sender) throw IllegalArgumentException("Envelope sender mismatch")
        if (to == null || bare(to) != roomJid) throw IllegalArgumentException("Envelope recipient mismatch")
        val content = envelope.child("content") ?: return ""
        return content.children.joinToString("") { if (it is XmlElement) it.toXml() else XmlElement.escapeText(it as String) }
    }

    private fun replacePrekey(id: Long) {
        keys.prekeys.remove(id.toString())
        keys.prekeys[keys.nextPrekeyId.toString()] = OmemoCrypto.generateKeyPair()
        keys.nextPrekeyId++
        saveKeys()
    }
}
