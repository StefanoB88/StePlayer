package com.steplayer.shared.epg

import com.steplayer.shared.util.CivilTime

data class XmltvChannel(val id: String, val displayName: String?, val icon: String?)

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Streaming, allocation-light XMLTV parser. Feed text chunks of any size via [feed] and call
 * [finish] at the end. Tolerates truncated files, unknown tags, CDATA, comments and entities.
 */
class XmltvParser(
    private val onChannel: (XmltvChannel) -> Unit = {},
    private val onProgramme: (XmltvProgramme) -> Unit,
) {
    private val buf = StringBuilder()
    private var pos = 0

    // Current element state
    private var inProgramme = false
    private var pChannel: String? = null
    private var pStart = 0L
    private var pStop = 0L
    private var pTitle: String? = null
    private var pDesc: String? = null

    private var inChannel = false
    private var cId: String? = null
    private var cName: String? = null
    private var cIcon: String? = null

    private var capture: String? = null // "title" | "desc" | "display-name"
    private val text = StringBuilder()

    fun feed(chunk: CharSequence) {
        buf.append(chunk)
        process()
        if (pos > 65_536) {
            buf.deleteRange(0, pos)
            pos = 0
        }
    }

    fun finish() {
        process()
        buf.clear()
        pos = 0
    }

    private fun process() {
        while (true) {
            val lt = buf.indexOf("<", pos)
            if (lt < 0) {
                if (capture != null) text.append(buf, pos, buf.length)
                pos = buf.length
                return
            }
            if (capture != null && lt > pos) text.append(buf, pos, lt)

            // CDATA
            if (buf.startsWith("<![CDATA[", lt)) {
                val end = buf.indexOf("]]>", lt + 9)
                if (end < 0) { pos = lt; return }
                if (capture != null) text.append(buf, lt + 9, end)
                pos = end + 3
                continue
            }
            // Comment
            if (buf.startsWith("<!--", lt)) {
                val end = buf.indexOf("-->", lt + 4)
                if (end < 0) { pos = lt; return }
                pos = end + 3
                continue
            }
            val gt = findTagEnd(lt + 1)
            if (gt < 0) { pos = lt; return }
            handleTag(lt + 1, gt)
            pos = gt + 1
        }
    }

    /** Finds the closing '>' of a tag, skipping '>' inside quoted attribute values. */
    private fun findTagEnd(from: Int): Int {
        var quote = 0.toChar()
        var i = from
        while (i < buf.length) {
            val c = buf[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    private fun handleTag(start: Int, end: Int) {
        val first = buf[start]
        if (first == '?' || first == '!') return
        if (first == '/') {
            val name = buf.substring(start + 1, end).trim()
            endElement(name)
            return
        }
        val selfClosing = buf[end - 1] == '/'
        val contentEnd = if (selfClosing) end - 1 else end
        var nameEnd = start
        while (nameEnd < contentEnd && !buf[nameEnd].isWhitespace()) nameEnd++
        val name = buf.substring(start, nameEnd)
        startElement(name, nameEnd, contentEnd)
        if (selfClosing) endElement(name)
    }

    private fun attr(from: Int, to: Int, key: String): String? {
        var i = from
        while (i < to) {
            while (i < to && buf[i].isWhitespace()) i++
            val ks = i
            while (i < to && buf[i] != '=' && !buf[i].isWhitespace()) i++
            val k = buf.substring(ks, i)
            while (i < to && buf[i].isWhitespace()) i++
            if (i >= to || buf[i] != '=') { i++; continue }
            i++
            while (i < to && buf[i].isWhitespace()) i++
            if (i >= to) return null
            val q = buf[i]
            val value: String
            if (q == '"' || q == '\'') {
                val e = buf.indexOf(q.toString(), i + 1).let { if (it < 0 || it > to) to else it }
                value = buf.substring(i + 1, e)
                i = e + 1
            } else {
                val s = i
                while (i < to && !buf[i].isWhitespace()) i++
                value = buf.substring(s, i)
            }
            if (k == key) return decodeEntities(value)
        }
        return null
    }

    private fun startElement(name: String, attrsFrom: Int, attrsTo: Int) {
        when (name) {
            "programme" -> {
                inProgramme = true
                pChannel = attr(attrsFrom, attrsTo, "channel")
                pStart = attr(attrsFrom, attrsTo, "start")?.let { CivilTime.parseXmltv(it) } ?: 0L
                pStop = attr(attrsFrom, attrsTo, "stop")?.let { CivilTime.parseXmltv(it) } ?: 0L
                pTitle = null
                pDesc = null
            }
            "channel" -> if (!inProgramme) {
                inChannel = true
                cId = attr(attrsFrom, attrsTo, "id")
                cName = null
                cIcon = null
            }
            "title" -> if (inProgramme && pTitle == null) beginCapture("title")
            "desc" -> if (inProgramme && pDesc == null) beginCapture("desc")
            "display-name" -> if (inChannel && cName == null) beginCapture("display-name")
            "icon" -> if (inChannel && cIcon == null) cIcon = attr(attrsFrom, attrsTo, "src")
        }
    }

    private fun beginCapture(what: String) {
        capture = what
        text.clear()
    }

    private fun endElement(name: String) {
        if (capture != null && name == capture) {
            val value = decodeEntities(text.toString().trim()).takeIf { it.isNotEmpty() }
            when (capture) {
                "title" -> pTitle = value
                "desc" -> pDesc = value
                "display-name" -> cName = value
            }
            capture = null
            text.clear()
            return
        }
        when (name) {
            "programme" -> {
                val ch = pChannel
                val title = pTitle
                if (inProgramme && ch != null && title != null && pStart > 0 && pStop > pStart) {
                    onProgramme(XmltvProgramme(ch, pStart, pStop, title, pDesc))
                }
                inProgramme = false
            }
            "channel" -> if (inChannel) {
                cId?.let { onChannel(XmltvChannel(it, cName, cIcon)) }
                inChannel = false
            }
        }
    }

    companion object {
        fun decodeEntities(s: String): String {
            if (s.indexOf('&') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i + 1)
                    if (semi in (i + 2)..(i + 10)) {
                        val ent = s.substring(i + 1, semi)
                        val decoded: String? = when {
                            ent == "amp" -> "&"
                            ent == "lt" -> "<"
                            ent == "gt" -> ">"
                            ent == "quot" -> "\""
                            ent == "apos" -> "'"
                            ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
                            ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { codePointToString(it) }
                            else -> null
                        }
                        if (decoded != null) {
                            sb.append(decoded)
                            i = semi + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun codePointToString(cp: Int): String = when {
            cp < 0 || cp > 0x10FFFF -> ""
            cp < 0x10000 -> cp.toChar().toString()
            else -> {
                val v = cp - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
        }
    }
}

private fun StringBuilder.startsWith(prefix: String, at: Int): Boolean {
    if (at + prefix.length > length) return false
    for (k in prefix.indices) if (this[at + k] != prefix[k]) return false
    return true
}
