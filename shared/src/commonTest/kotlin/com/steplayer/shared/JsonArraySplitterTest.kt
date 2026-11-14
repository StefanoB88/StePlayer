package com.steplayer.shared

import com.steplayer.shared.xtream.JsonArraySplitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonArraySplitterTest {
    /** Splits [json] feeding it in chunks of [chunk] bytes. */
    private fun split(json: String, chunk: Int): List<String> {
        val out = ArrayList<String>()
        val s = JsonArraySplitter { b, n -> out += b.decodeToString(0, n) }
        val bytes = json.encodeToByteArray()
        var i = 0
        while (i < bytes.size && !s.isDone) {
            val part = bytes.copyOfRange(i, minOf(bytes.size, i + chunk))
            s.feed(part, part.size)
            i += chunk
        }
        s.finish()
        return out
    }

    @Test fun splitsElementsAcrossAnyChunkBoundary() {
        val json = "﻿ [ {\"name\":\"Città [1], \\\"bella\\\" {x}\",\"n\":1,\"tags\":[1,[2,3]],\"o\":{\"a\":\"]\"}} ,\n" +
            "{\"name\":\"Ünïcødé 𝄞\"}, \"scalare\", 42, null, [1,2] ]  trailing garbage"
        val expected = listOf(
            "{\"name\":\"Città [1], \\\"bella\\\" {x}\",\"n\":1,\"tags\":[1,[2,3]],\"o\":{\"a\":\"]\"}}",
            "{\"name\":\"Ünïcødé 𝄞\"}", "\"scalare\"", "42", "null", "[1,2]",
        )
        for (chunk in listOf(1, 2, 3, 7, 64, 100_000)) assertEquals(expected, split(json, chunk), "chunk=$chunk")
    }

    @Test fun emptyShapesYieldNothing() {
        for (body in listOf("", "   ", "[]", " [ ] ", "{}", "{\"user_info\":{}}", "null")) {
            assertTrue(split(body, 3).isEmpty(), "body=<$body>")
        }
    }

    @Test fun brokenBodiesFail() {
        assertFailsWith<IllegalStateException> { split("[{\"a\":1},{\"b\":", 4) }
        assertFailsWith<IllegalStateException> { split("<html>Errore</html>", 4) }
    }
}
