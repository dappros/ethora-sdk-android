package com.ethora.chat.core.e2ee

/**
 * A small XML element tree for the stanzas OMEMO has to rewrite.
 *
 * The rest of the SDK works on raw stanza strings, so OMEMO parses only what
 * it needs, rewrites it, and serializes it back. Namespaces are not resolved
 * into prefixes: an element's namespace is its own `xmlns` attribute, or the
 * nearest ancestor's, which is what XMPP senders produce.
 */
class XmlElement(
    val name: String,
    val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    children: List<Any> = emptyList()
) {
    private val kids = ArrayList<Any>()
    var parent: XmlElement? = null
        private set

    init {
        children.forEach { add(it) }
    }

    /** Child elements and text runs (String), in document order. */
    val children: List<Any> get() = kids

    /** Adds an element or text run. Elements taken from another tree are detached from it first. */
    fun add(child: Any): XmlElement {
        when (child) {
            is XmlElement -> {
                child.parent?.kids?.remove(child)
                child.parent = this
            }
            is String -> Unit
            else -> throw IllegalArgumentException("Unsupported child: $child")
        }
        kids.add(child)
        return this
    }

    fun addAll(items: Collection<Any>): XmlElement {
        items.forEach { add(it) }
        return this
    }

    /** The namespace in effect: this element's `xmlns`, or the nearest ancestor's. */
    val namespace: String?
        get() = attrs["xmlns"] ?: parent?.namespace

    val elements: List<XmlElement> get() = kids.filterIsInstance<XmlElement>()

    fun children(name: String, ns: String? = null): List<XmlElement> =
        elements.filter { it.name == name && (ns == null || it.namespace == ns) }

    fun child(name: String, ns: String? = null): XmlElement? = children(name, ns).firstOrNull()

    fun attr(name: String): String? = attrs[name]

    /** Concatenated text of the direct text runs. */
    fun text(): String = kids.filterIsInstance<String>().joinToString("")

    /** Removes every direct child element with this name (and namespace, when given). */
    fun removeChildren(name: String, ns: String? = null) {
        val doomed = children(name, ns)
        doomed.forEach { kids.remove(it) }
        doomed.forEach { it.parent = null }
    }

    fun toXml(): String {
        val sb = StringBuilder()
        write(sb)
        return sb.toString()
    }

    private fun write(sb: StringBuilder) {
        sb.append('<').append(name)
        for ((k, v) in attrs) sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"')
        if (kids.isEmpty()) {
            sb.append("/>")
            return
        }
        sb.append('>')
        for (kid in kids) {
            when (kid) {
                is XmlElement -> kid.write(sb)
                is String -> sb.append(escapeText(kid))
            }
        }
        sb.append("</").append(name).append('>')
    }

    override fun toString(): String = toXml()

    companion object {
        fun escapeText(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        fun escapeAttr(value: String): String = escapeText(value)
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

/** Parser for the XML subset XMPP stanzas use. */
object XmlParser {
    fun parse(input: String): XmlElement {
        val p = Parser(input)
        p.skipProlog()
        val root = p.element() ?: throw IllegalArgumentException("No root element")
        p.skipMisc()
        if (!p.atEnd()) throw IllegalArgumentException("Trailing content after root element")
        return root
    }

    /** Parses a fragment that may hold several sibling elements, wrapped in a throwaway root. */
    fun parseFragments(input: String): List<Any> {
        return parse("<fragment>$input</fragment>").children.toList()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length

        fun skipProlog() {
            skipWs()
            while (startsWith("<?") || startsWith("<!--") || startsWith("<!DOCTYPE")) {
                if (startsWith("<?")) skipUntil("?>")
                else if (startsWith("<!--")) skipUntil("-->")
                else skipUntil(">")
                skipWs()
            }
        }

        fun skipMisc() {
            skipWs()
            while (startsWith("<!--") || startsWith("<?")) {
                if (startsWith("<?")) skipUntil("?>") else skipUntil("-->")
                skipWs()
            }
        }

        fun element(): XmlElement? {
            skipWs()
            if (atEnd() || s[pos] != '<') return null
            pos++ // '<'
            val name = readName()
            val attrs = LinkedHashMap<String, String>()
            while (true) {
                skipWs()
                if (atEnd()) throw IllegalArgumentException("Unterminated tag <$name")
                if (s[pos] == '/') {
                    expect("/>")
                    return XmlElement(name, attrs)
                }
                if (s[pos] == '>') {
                    pos++
                    break
                }
                val attrName = readName()
                skipWs()
                expect("=")
                skipWs()
                val quote = s.getOrNull(pos) ?: throw IllegalArgumentException("Attribute without value")
                if (quote != '"' && quote != '\'') throw IllegalArgumentException("Unquoted attribute")
                pos++
                val end = s.indexOf(quote, pos)
                if (end < 0) throw IllegalArgumentException("Unterminated attribute")
                attrs[attrName] = unescape(s.substring(pos, end))
                pos = end + 1
            }
            val element = XmlElement(name, attrs)
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated element <$name>")
                if (startsWith("</")) {
                    pos += 2
                    val closing = readName()
                    if (closing != name) throw IllegalArgumentException("Mismatched </$closing> for <$name>")
                    skipWs()
                    expect(">")
                    return element
                }
                if (startsWith("<!--")) {
                    skipUntil("-->")
                    continue
                }
                if (s[pos] == '<') {
                    element.add(element() ?: throw IllegalArgumentException("Bad child"))
                } else {
                    val next = s.indexOf('<', pos).let { if (it < 0) s.length else it }
                    val text = unescape(s.substring(pos, next))
                    if (text.isNotEmpty()) element.add(text)
                    pos = next
                }
            }
        }

        private fun readName(): String {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] in "-_.:")) pos++
            if (pos == start) throw IllegalArgumentException("Expected a name at $pos")
            return s.substring(start, pos)
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun startsWith(prefix: String) = s.startsWith(prefix, pos)

        private fun expect(token: String) {
            if (!s.startsWith(token, pos)) throw IllegalArgumentException("Expected '$token' at $pos")
            pos += token.length
        }

        private fun skipUntil(token: String) {
            val end = s.indexOf(token, pos)
            if (end < 0) throw IllegalArgumentException("Missing '$token'")
            pos = end + token.length
        }

        private fun unescape(value: String): String {
            if (!value.contains('&')) return value
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '&') {
                    out.append(c)
                    i++
                    continue
                }
                val semi = value.indexOf(';', i)
                if (semi < 0) throw IllegalArgumentException("Bare '&'")
                val entity = value.substring(i + 1, semi)
                out.append(
                    when {
                        entity == "amp" -> "&"
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity.startsWith("#x") -> String(Character.toChars(entity.substring(2).toInt(16)))
                        entity.startsWith("#") -> String(Character.toChars(entity.substring(1).toInt()))
                        else -> throw IllegalArgumentException("Unknown entity &$entity;")
                    }
                )
                i = semi + 1
            }
            return out.toString()
        }
    }
}
