package com.steplayer.shared.m3u

data class M3uEntry(
    val title: String,
    val url: String,
    val attributes: Map<String, String>,
    val group: String?,
    val userAgent: String?,
    val referrer: String?,
) {
    val tvgId: String? get() = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
    val tvgName: String? get() = attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    val logo: String? get() = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.isNotBlank() }
    val channelNumber: Int? get() = (attributes["tvg-chno"] ?: attributes["channel-number"])?.toIntOrNull()
    val catchupDays: Int get() = (attributes["catchup-days"] ?: attributes["timeshift"])?.toIntOrNull() ?: 0
    val catchupSource: String? get() = attributes["catchup-source"]?.takeIf { it.isNotBlank() }
    val catchupMode: String? get() = attributes["catchup"]?.lowercase()
    val displayName: String get() = title.ifBlank { tvgName ?: url.substringAfterLast('/') }
}

data class M3uHeader(val epgUrls: List<String>, val userAgent: String?)

/**
 * Incremental M3U / M3U8 (IPTV "extended") parser. Feed it one line at a time; it never
 * holds the whole list in memory, so 200k-entry lists are fine on low-end TV boxes.
 */
class M3uParser(private val onEntry: (M3uEntry) -> Unit) {

    var header: M3uHeader = M3uHeader(emptyList(), null)
        private set

    private var pendingTitle: String? = null
    private var pendingAttrs: Map<String, String> = emptyMap()
    private var pendingGroup: String? = null
    private var pendingUa: String? = null
    private var pendingRef: String? = null
    var entryCount = 0
        private set

    fun feed(rawLine: String) {
        val line = rawLine.trim().removePrefix("﻿")
        if (line.isEmpty()) return
        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> parseHeader(line)
            line.startsWith("#EXTINF", ignoreCase = true) -> parseExtInf(line)
            line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> parseVlcOpt(line.substringAfter(':'))
            line.startsWith("#") -> Unit // #KODIPROP, #EXT-X-..., comments
            else -> emit(line)
        }
    }

    private fun parseHeader(line: String) {
        val attrs = parseAttributes(line, line.indexOf(' ').let { if (it < 0) line.length else it })
        val epg = listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"], attrs["tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        header = M3uHeader(epgUrls = epg, userAgent = attrs["user-agent"] ?: attrs["http-user-agent"])
    }

    private fun parseVlcOpt(opt: String) {
        val key = opt.substringBefore('=').trim().lowercase()
        val value = opt.substringAfter('=', "").trim()
        when (key) {
            "http-user-agent" -> pendingUa = value
            "http-referrer", "http-referer" -> pendingRef = value
        }
    }

    private fun parseExtInf(line: String) {
        // #EXTINF:-1 tvg-id="x" group-title="News, World",Display, Name
        var i = line.indexOf(':') + 1
        if (i <= 0) i = line.length
        // skip duration
        while (i < line.length && line[i] != ' ' && line[i] != ',') i++
        val attrsAndTitle = parseAttributesUntilTitle(line, i)
        pendingAttrs = attrsAndTitle.first
        pendingTitle = attrsAndTitle.second
        attrsAndTitle.first["group-title"]?.let { pendingGroup = it.ifBlank { null } }
    }

    private fun emit(url: String) {
        val title = pendingTitle
        if (title == null && !url.contains("://")) return
        val entry = M3uEntry(
            title = title ?: "",
            url = url,
            attributes = pendingAttrs,
            group = pendingGroup,
            userAgent = pendingUa ?: header.userAgent,
            referrer = pendingRef,
        )
        pendingTitle = null
        pendingAttrs = emptyMap()
        pendingGroup = null
        pendingUa = null
        pendingRef = null
        entryCount++
        onEntry(entry)
    }

    companion object {
        /** Parses key="value" pairs (quotes optional) starting at [from]; stops at the first unquoted comma. */
        internal fun parseAttributesUntilTitle(line: String, from: Int): Pair<Map<String, String>, String> {
            val attrs = LinkedHashMap<String, String>()
            var i = from
            val n = line.length
            while (i < n) {
                while (i < n && line[i] == ' ') i++
                if (i >= n) break
                if (line[i] == ',') {
                    return attrs to line.substring(i + 1).trim()
                }
                val keyStart = i
                while (i < n && line[i] != '=' && line[i] != ' ' && line[i] != ',') i++
                val key = line.substring(keyStart, i).lowercase()
                if (i < n && line[i] == '=') {
                    i++
                    val value: String
                    if (i < n && (line[i] == '"' || line[i] == '\'')) {
                        val quote = line[i]
                        val end = line.indexOf(quote, i + 1).let { if (it < 0) n else it }
                        value = line.substring(i + 1, end)
                        i = (end + 1).coerceAtMost(n)
                    } else {
                        val start = i
                        while (i < n && line[i] != ' ' && line[i] != ',') i++
                        value = line.substring(start, i)
                    }
                    if (key.isNotEmpty()) attrs[key] = value.trim()
                }
            }
            return attrs to ""
        }

        internal fun parseAttributes(line: String, from: Int): Map<String, String> =
            parseAttributesUntilTitle("$line,", from).first
    }
}

/** Classification of an M3U entry into the app's three sections. */
enum class M3uKind { LIVE, MOVIE, SERIES }

object M3uClassifier {
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv", "mpg", "mpeg")
    private val EPISODE_REGEX = Regex(
        "^(.*?)[\\s._\\-|]*(?:[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})|(\\d{1,2})x(\\d{1,3}))",
    )

    fun classify(entry: M3uEntry): M3uKind {
        val path = entry.url.substringBefore('?').lowercase()
        if ("/series/" in path) return M3uKind.SERIES
        if ("/movie/" in path) return M3uKind.MOVIE
        val ext = path.substringAfterLast('.', "")
        if (ext in VOD_EXTENSIONS) {
            if (parseEpisode(entry.displayName) != null) return M3uKind.SERIES
            // Some lists serve live channels as ".mp4"/".ts"-like files: guide id, channel number or
            // replay data only make sense for a channel, never for a film.
            val looksLikeChannel = entry.tvgId != null || entry.channelNumber != null ||
                entry.catchupDays > 0 || entry.catchupSource != null
            return if (looksLikeChannel) M3uKind.LIVE else M3uKind.MOVIE
        }
        return M3uKind.LIVE
    }

    data class EpisodeRef(val showName: String, val season: Int, val episode: Int)

    fun parseEpisode(title: String): EpisodeRef? {
        val m = EPISODE_REGEX.find(title) ?: return null
        val show = m.groupValues[1].trim().trimEnd('-', '|', ':', '.').trim()
        val season = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return null
        val ep = (m.groupValues[3].ifEmpty { m.groupValues[5] }).toIntOrNull() ?: return null
        if (show.isEmpty()) return null
        return EpisodeRef(show, season, ep)
    }
}
