package com.steplayer.shared.xtream

/**
 * Incremental splitter for a top-level JSON array: feed it raw UTF-8 chunks as they arrive and
 * it calls [onElement] with the bytes of each complete element, so callers can decode a huge
 * array one item at a time. Works on bytes: JSON's structural characters are ASCII and never
 * appear inside a UTF-8 multi-byte sequence, so chunk boundaries can fall anywhere.
 *
 * An object, `null` or an empty body (what some servers send for "no items") yields no elements.
 * Anything else (e.g. an HTML error page) or a truncated array makes [finish] throw
 * [IllegalStateException], so a broken response can never be mistaken for an empty catalog.
 * The byte array passed to [onElement] is reused: consume it before returning.
 */
internal class JsonArraySplitter(private val onElement: (bytes: ByteArray, length: Int) -> Unit) {
    private var buf = ByteArray(4 * 1024)
    private var len = 0

    /** null = top-level value not seen yet. */
    private var isArray: Boolean? = null
    private var firstByte: Byte = 0
    private var inElement = false
    private var depth = 0
    private var inString = false
    private var escape = false

    /** True once the closing bracket (or a non-array body) was seen: the rest can be ignored. */
    var isDone = false
        private set

    fun feed(bytes: ByteArray, count: Int) {
        var i = 0
        while (i < count && !isDone) {
            step(bytes[i])
            i++
        }
    }

    fun finish() {
        when (isArray) {
            true -> if (!isDone) error("Truncated JSON array")
            false -> if (firstByte != LBRACE && firstByte != LOWER_N) error("Not a JSON array")
            null -> Unit // empty body
        }
    }

    private fun step(b: Byte) {
        if (isArray == null) {
            if (isWhitespace(b) || b == BOM_1 || b == BOM_2 || b == BOM_3) return
            firstByte = b
            if (b == LBRACKET) {
                isArray = true
            } else {
                isArray = false
                isDone = true
            }
            return
        }
        if (!inElement) {
            when {
                isWhitespace(b) || b == COMMA -> return
                b == RBRACKET -> { isDone = true; return }
                else -> { inElement = true; len = 0; depth = 0 }
            }
        }
        if (inString) {
            append(b)
            when {
                escape -> escape = false
                b == BACKSLASH -> escape = true
                b == QUOTE -> inString = false
            }
            return
        }
        when (b) {
            QUOTE -> { inString = true; append(b) }
            LBRACE, LBRACKET -> { depth++; append(b) }
            RBRACE, RBRACKET -> {
                if (depth == 0 && b == RBRACKET) { // closes the outer array right after an element
                    emit()
                    isDone = true
                } else {
                    depth--
                    append(b)
                }
            }
            COMMA -> if (depth == 0) emit() else append(b)
            else -> if (!(depth == 0 && isWhitespace(b))) append(b)
        }
    }

    private fun emit() {
        inElement = false
        if (len > 0) onElement(buf, len)
        len = 0
    }

    private fun append(b: Byte) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = b
    }

    private fun isWhitespace(b: Byte) = b == SPACE || b == TAB || b == LF || b == CR

    private companion object {
        const val LBRACKET = '['.code.toByte()
        const val RBRACKET = ']'.code.toByte()
        const val LBRACE = '{'.code.toByte()
        const val RBRACE = '}'.code.toByte()
        const val LOWER_N = 'n'.code.toByte()
        const val COMMA = ','.code.toByte()
        const val QUOTE = '"'.code.toByte()
        const val BACKSLASH = '\\'.code.toByte()
        const val SPACE = ' '.code.toByte()
        const val TAB = '\t'.code.toByte()
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val BOM_1 = 0xEF.toByte()
        const val BOM_2 = 0xBB.toByte()
        const val BOM_3 = 0xBF.toByte()
    }
}
