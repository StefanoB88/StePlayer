package com.steplayer.shared

import com.steplayer.shared.epg.TextChunkDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeGzipTest {
    private val gz = byteArrayOf(31,-117,8,0,0,0,0,0,2,-1,-19,-57,33,1,0,32,12,0,-80,42,-17,-14,36,72,20,37,-88,-63,53,61,40,68,4,60,25,54,-73,-20,109,-60,-39,113,-41,-84,72,17,17,17,17,17,17,17,-7,-13,0,83,65,-97,-115,60,15,0,0)

    @Test fun inflatesWithZlib() {
        for (chunk in listOf(1, 5, 4096)) {
            val sb = StringBuilder()
            val dec = TextChunkDecoder { sb.append(it) }
            var i = 0
            while (i < gz.size) {
                val n = minOf(chunk, gz.size - i)
                dec.feed(gz.copyOfRange(i, i + n), n)
                i += n
            }
            dec.finish()
            assertEquals("Ciao è 𝄞 ".repeat(300), sb.toString(), "chunk=$chunk")
        }
    }
}
