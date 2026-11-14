package com.steplayer.shared.util

/** Lowercase, accent-free, single-spaced version of a title used for fast LIKE search. */
fun normalizeForSearch(input: String): String {
    val sb = StringBuilder(input.length)
    var lastSpace = true
    for (c in input) {
        val mapped = foldChar(c.lowercaseChar())
        if (mapped == ' ') {
            if (!lastSpace) sb.append(' ')
            lastSpace = true
        } else {
            sb.append(mapped)
            lastSpace = false
        }
    }
    return sb.toString().trimEnd()
}

private fun foldChar(c: Char): Char = when (c) {
    'à', 'á', 'â', 'ã', 'ä', 'å', 'ā' -> 'a'
    'è', 'é', 'ê', 'ë', 'ē', 'ė', 'ę' -> 'e'
    'ì', 'í', 'î', 'ï', 'ī' -> 'i'
    'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō' -> 'o'
    'ù', 'ú', 'û', 'ü', 'ū' -> 'u'
    'ç', 'ć', 'č' -> 'c'
    'ñ', 'ń' -> 'n'
    'ß' -> 's'
    'š', 'ś' -> 's'
    'ž', 'ź', 'ż' -> 'z'
    'ý', 'ÿ' -> 'y'
    '\t', '\n', '\r', '_', '.', '-', '|', ':' -> ' '
    else -> c
}

/** Extracts a 4 digit year from strings like "2019-05-01", "2019" or "Movie (2019)". */
fun extractYear(input: String?): Int? {
    if (input.isNullOrBlank()) return null
    val match = YEAR_REGEX.find(input) ?: return null
    return match.value.toIntOrNull()
}

private val YEAR_REGEX = Regex("(19|20)\\d{2}")

/** Stable, platform independent 64-bit FNV-1a hash rendered as hex. */
fun stableHash(input: String): String {
    var hash = -0x340d631b7bdddcdbL // FNV offset basis
    for (c in input) {
        hash = hash xor c.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}

/** Masks credentials so URLs can be shown in error messages without leaking them. */
fun redactUrl(url: String): String =
    url.replace(Regex("(username|password)=[^&]*"), "$1=***")
        .replace(Regex("/(live|movie|series|timeshift)/[^/]+/[^/]+/"), "/$1/***/***/")
