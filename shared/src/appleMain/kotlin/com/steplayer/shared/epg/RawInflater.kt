package com.steplayer.shared.epg

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import platform.posix.memcpy
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

internal actual class RawInflater actual constructor() {
    private val stream = nativeHeap.alloc<z_stream>()
    private var inputBuffer: CPointer<ByteVar>? = null
    private var closed = false
    private var done = false

    init {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.avail_in = 0u
        stream.next_in = null
        val rc = inflateInit2_(stream.ptr, -15, zlibVersion()?.toKString(), sizeOf<z_stream>().convert())
        check(rc == Z_OK) { "inflateInit2 failed: $rc" }
    }

    actual fun setInput(bytes: ByteArray, offset: Int, length: Int) {
        inputBuffer?.let { nativeHeap.free(it) }
        val buf = nativeHeap.allocArray<ByteVar>(length)
        bytes.usePinned { memcpy(buf, it.addressOf(offset), length.convert()) }
        inputBuffer = buf
        stream.next_in = buf.reinterpret()
        stream.avail_in = length.convert()
    }

    actual fun inflate(out: ByteArray): Int {
        if (done) return 0
        return out.usePinned { pinned ->
            stream.next_out = pinned.addressOf(0).reinterpret()
            stream.avail_out = out.size.convert()
            val rc = inflate(stream.ptr, Z_NO_FLUSH)
            when (rc) {
                Z_STREAM_END -> done = true
                Z_OK, Z_BUF_ERROR -> Unit
                else -> error("zlib inflate error $rc")
            }
            out.size - stream.avail_out.toInt()
        }
    }

    actual val finished: Boolean get() = done
    actual val needsInput: Boolean get() = stream.avail_in.toInt() == 0

    actual fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        inputBuffer?.let { nativeHeap.free(it) }
        inputBuffer = null
        nativeHeap.free(stream)
    }
}
