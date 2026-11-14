package com.steplayer.shared.epg

import java.util.zip.Inflater

internal actual class RawInflater actual constructor() {
    private val inflater = Inflater(true)
    actual fun setInput(bytes: ByteArray, offset: Int, length: Int) = inflater.setInput(bytes, offset, length)
    actual fun inflate(out: ByteArray): Int = inflater.inflate(out)
    actual val finished: Boolean get() = inflater.finished()
    actual val needsInput: Boolean get() = inflater.needsInput()
    actual fun close() = inflater.end()
}
