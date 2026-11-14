package com.steplayer.shared.search

import com.steplayer.shared.util.normalizeForSearch

/**
 * Turns free text into FTS4 terms.
 * - accent/case-insensitive (same normalisation used when indexing)
 * - Italian/English stop-words dropped ("il", "di", "the"...) unless they're all the user typed
 * - light Italian stemming: "napoli" → prefix "napol*" so it also finds "napoletano", "napoletana"
 */
internal object QueryParser {

    private val STOP_WORDS = setOf(
        "il", "lo", "la", "i", "gli", "le", "l", "un", "una", "uno", "di", "del", "della", "dello", "dei",
        "degli", "delle", "da", "dal", "dalla", "in", "nel", "nella", "con", "su", "sul", "sulla", "per",
        "tra", "fra", "e", "ed", "o", "a", "al", "alla", "ai", "agli", "alle", "che",
        "the", "of", "and", "an", "on", "to", "at", "for",
    )
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    /** Splits like SQLite's "simple" tokenizer: ASCII non-alphanumerics are separators. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in normalizeForSearch(text)) {
            val separator = c.code < 128 && !c.isLetterOrDigit()
            if (separator) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            } else sb.append(c)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun meaningfulTokens(text: String): List<String> {
        val all = tokenize(text)
        val filtered = all.filter { it !in STOP_WORDS }
        return (filtered.ifEmpty { all }).distinct()
    }

    /** The prefix actually sent to FTS for one token. */
    fun stem(token: String): String =
        if (token.length >= 5 && token.last() in VOWELS && token.all { it.code < 128 }) token.dropLast(1) else token

    /** FTS4 MATCH expression (implicit AND between terms, prefix match on each). */
    fun ftsExpression(tokens: List<String>): String = tokens.joinToString(" ") { "${stem(it)}*" }
}

/** Bounded Damerau–Levenshtein (optimal string alignment) distance. Returns max+1 if exceeded. */
internal fun editDistance(a: String, b: String, max: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > max) return max + 1
    val n = a.length
    val m = b.length
    var prev2 = IntArray(m + 1)
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)
    for (i in 1..n) {
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
            cur[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > max) return max + 1
        val t = prev2; prev2 = prev; prev = cur; cur = t
    }
    return prev[m]
}
