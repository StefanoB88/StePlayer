package com.steplayer.shared.epg

/** Platform raw-DEFLATE inflater (zlib with no header). */
internal expect class RawInflater() {
    fun setInput(bytes: ByteArray, offset: Int, length: Int)
    fun inflate(out: ByteArray): Int
    val finished: Boolean
    val needsInput: Boolean
    fun close()
}

/**
 * Turns a stream of byte chunks (possibly gzip-compressed) into text chunks.
 * Detects gzip by magic number, so it works for "xmltv.php" and "guide.xml.gz" alike.
 */
internal class TextChunkDecoder(private val onText: (String) -> Unit) {
    private enum class Mode { DETECT, PLAIN, GZIP_HEADER, GZIP_BODY, DONE }

    private var mode = Mode.DETECT
    private val header = ByteArrayBuilder()
    private var inflater: RawInflater? = null
    private val utf8 = Utf8Decoder()
    private val out = ByteArray(64 * 1024)

    fun feed(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        when (mode) {
            Mode.DETECT -> {
                header.append(bytes, 0, length)
                if (header.size < 2) return
                val all = header.toByteArray()
                header.clear()
                if (all[0] == 0x1f.toByte() && all[1] == 0x8b.toByte()) {
                    mode = Mode.GZIP_HEADER
                } else {
                    mode = Mode.PLAIN
                }
                feed(all, all.size)
            }
            Mode.PLAIN -> utf8.decode(bytes, 0, length, onText)
            Mode.GZIP_HEADER -> {
                header.append(bytes, 0, length)
                val all = header.toByteArray()
                val bodyStart = gzipHeaderLength(all) ?: return // need more bytes
                header.clear()
                inflater = RawInflater()
                mode = Mode.GZIP_BODY
                inflateChunk(all, bodyStart, all.size - bodyStart)
            }
            Mode.GZIP_BODY -> inflateChunk(bytes, 0, length)
            Mode.DONE -> Unit
        }
    }

    private fun inflateChunk(bytes: ByteArray, offset: Int, length: Int) {
        val inf = inflater ?: return
        if (length > 0) inf.setInput(bytes, offset, length)
        while (!inf.finished) {
            val n = inf.inflate(out)
            if (n > 0) utf8.decode(out, 0, n, onText)
            if (n == 0 && inf.needsInput) break
        }
        if (inf.finished) {
            inf.close()
            inflater = null
            mode = Mode.DONE
        }
    }

    fun finish() {
        if (mode == Mode.DETECT && header.size > 0) {
            val all = header.toByteArray(); header.clear(); mode = Mode.PLAIN; feed(all, all.size)
        }
        inflater?.close()
        inflater = null
        utf8.flush(onText)
    }

    companion object {
        /** Returns the offset of the deflate body, or null if [h] doesn't yet contain the full header. */
        fun gzipHeaderLength(h: ByteArray): Int? {
            if (h.size < 10) return null
            val flags = h[3].toInt() and 0xff
            var i = 10
            if (flags and 0x04 != 0) { // FEXTRA
                if (h.size < i + 2) return null
                val xlen = (h[i].toInt() and 0xff) or ((h[i + 1].toInt() and 0xff) shl 8)
                i += 2 + xlen
            }
            if (flags and 0x08 != 0) { // FNAME
                while (true) { if (i >= h.size) return null; if (h[i++] == 0.toByte()) break }
            }
            if (flags and 0x10 != 0) { // FCOMMENT
                while (true) { if (i >= h.size) return null; if (h[i++] == 0.toByte()) break }
            }
            if (flags and 0x02 != 0) i += 2 // FHCRC
            return if (i <= h.size) i else null
        }
    }
}

/** UTF-8 decoder that correctly handles multi-byte sequences split across chunks. */
internal class Utf8Decoder {
    private val carry = ByteArray(4)
    private var carryLen = 0

    fun decode(bytes: ByteArray, offset: Int, length: Int, onText: (String) -> Unit) {
        var data = bytes
        var start = offset
        var end = offset + length
        if (carryLen > 0) {
            val merged = ByteArray(carryLen + length)
            carry.copyInto(merged, 0, 0, carryLen)
            bytes.copyInto(merged, carryLen, offset, offset + length)
            data = merged; start = 0; end = merged.size
            carryLen = 0
        }
        // Find a safe cut point: don't split a multi-byte sequence.
        var cut = end
        var back = 0
        while (back < 3 && cut - 1 - back >= start) {
            val b = data[cut - 1 - back].toInt() and 0xff
            if (b and 0xC0 == 0x80) { back++; continue } // continuation byte
            val need = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            if (need > back + 1) cut = cut - 1 - back
            break
        }
        if (cut < end) {
            carryLen = end - cut
            data.copyInto(carry, 0, cut, end)
        }
        if (cut > start) onText(data.decodeToString(start, cut))
    }

    fun flush(onText: (String) -> Unit) {
        if (carryLen > 0) onText(carry.decodeToString(0, carryLen))
        carryLen = 0
    }
}

internal class ByteArrayBuilder {
    private var data = ByteArray(64)
    var size = 0
        private set

    fun append(src: ByteArray, offset: Int, length: Int) {
        if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
        src.copyInto(data, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
    fun clear() { size = 0 }
}
