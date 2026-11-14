package com.steplayer.shared

import com.steplayer.shared.search.QueryParser
import com.steplayer.shared.search.editDistance
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryParserTest {
    @Test fun tokensAndStems() {
        assertEquals(listOf("tesoro", "napoli"), QueryParser.meaningfulTokens("Il tesoro di Napoli"))
        assertEquals(listOf("di"), QueryParser.meaningfulTokens("di")) // only stop-words → keep them
        assertEquals("napol* mare*", QueryParser.ftsExpression(listOf("napoli", "mare")))
        assertEquals(listOf("citta", "nascosta"), QueryParser.meaningfulTokens("Città-nascosta!"))
    }

    @Test fun distance() {
        assertEquals(1, editDistance("nappoli", "napoli", 2))
        assertEquals(1, editDistance("naploi", "napoli", 2)) // transposition
        assertEquals(3, editDistance("roma", "napoli", 2))
    }
}
