package com.steplayer.shared.search

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.steplayer.shared.data.toModel
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.normalizeForSearch
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Global "search everything" engine: channels, movies, series (incl. plot/genre) and every
 * programme in the TV guide — live now, upcoming and replayable (catch-up).
 *
 * Searching "Napoli" returns the event airing now, tomorrow's documentary,
 * yesterday's news in replay, a channel with "Napoli" in its name, films and series set in Naples.
 */
class SmartSearch internal constructor(
    private val db: StePlayerDatabase,
    private val index: SearchIndex,
) {
    private val q get() = db.schemaQueries

    // ------------------------------------------------------------------ recent queries

    val recentQueries: CFlow<List<String>> = q.getSetting(KEY_RECENT).asFlow().mapToOneOrNull(Dispatchers.IO)
        .map { it?.split('\n')?.filter { s -> s.isNotBlank() } ?: emptyList() }.wrap()

    @Throws(Throwable::class)
    suspend fun rememberQuery(query: String) = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext
        val current = q.getSetting(KEY_RECENT).executeAsOneOrNull()?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        val updated = (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_RECENT)
        q.putSetting(KEY_RECENT, updated.joinToString("\n"))
    }

    @Throws(Throwable::class)
    suspend fun clearRecentQueries() = withContext(Dispatchers.IO) { q.deleteSetting(KEY_RECENT) }

    // ------------------------------------------------------------------ search

    @Throws(Throwable::class)
    suspend fun search(sourceId: Long, query: String): SmartSearchResults = withContext(Dispatchers.IO) {
        val tokens = QueryParser.meaningfulTokens(query)
        val isNumber = tokens.size == 1 && tokens[0].all { it.isDigit() }
        if (tokens.isEmpty() || (!isNumber && tokens.sumOf { it.length } < 2)) return@withContext SmartSearchResults.empty(query)

        runCatching { ensureIndexes(sourceId) }
        if (!index.hasCatalog(sourceId)) return@withContext fallback(sourceId, query, tokens)
        val now = nowMillis()
        val first = run(sourceId, query, tokens, corrected = null, now = now)
        if (!first.isEmpty) return@withContext first

        val fixed = index.correct(sourceId, tokens) ?: return@withContext first
        run(sourceId, query, fixed, corrected = fixed.joinToString(" "), now = now)
    }

    /** Plain LIKE search, used only if the device's SQLite lacks FTS4 (never seen in practice). */
    private fun fallback(sourceId: Long, query: String, tokens: List<String>): SmartSearchResults {
        val term = tokens.joinToString(" ")
        val scorer = Scorer(term, tokens)
        return SmartSearchResults.empty(query).copy(
            terms = tokens,
            channels = q.searchChannels(sourceId, term, 40).executeAsList().map { SearchHit.ChannelHit(it.toModel(), scorer.title(it.search_name)) },
            movies = q.searchMovies(sourceId, term, 120).executeAsList().map { SearchHit.MovieHit(it.toModel(), scorer.title(it.search_name)) },
            series = q.searchSeries(sourceId, term, 120).executeAsList().map { SearchHit.SeriesHit(it.toModel(), scorer.title(it.search_name), false) },
        )
    }

    /** Builds missing indexes (e.g. lists synced by an older app version). */
    private fun ensureIndexes(sourceId: Long) {
        if (!index.hasCatalog(sourceId)) index.rebuildCatalog(sourceId)
        if (!index.hasEpg(sourceId)) index.rebuildEpg(sourceId)
    }

    private fun run(sourceId: Long, query: String, tokens: List<String>, corrected: String?, now: Long): SmartSearchResults {
        val expr = QueryParser.ftsExpression(tokens)
        val queryNorm = tokens.joinToString(" ")
        val scorer = Scorer(queryNorm, tokens)

        // ---- catalog
        val docids = index.matchCatalog(sourceId, expr, CATALOG_LIMIT)
        val channelRows = docids.filter { it % 4 == 0L }.map { it / 4 }
        val movieRows = docids.filter { it % 4 == 1L }.map { it / 4 }
        val seriesRows = docids.filter { it % 4 == 2L }.map { it / 4 }

        val channels = LinkedHashMap<String, SearchHit.ChannelHit>()
        if (tokens.size == 1 && tokens[0].all { it.isDigit() }) {
            q.channelsByNumber(sourceId, tokens[0].toLong()).executeAsList().forEach {
                channels[it.id] = SearchHit.ChannelHit(it.toModel(), 120)
            }
        }
        channelRows.chunked(CHUNK).flatMap { q.channelsByRowids(it).executeAsList() }.forEach { row ->
            if (row.id !in channels) channels[row.id] = SearchHit.ChannelHit(row.toModel(), scorer.title(row.search_name) + 5)
        }
        val movies = movieRows.chunked(CHUNK).flatMap { q.moviesByRowids(it).executeAsList() }.map { row ->
            val bonus = ((row.rating ?: 0.0).coerceIn(0.0, 10.0) / 2).toInt()
            SearchHit.MovieHit(row.toModel(), scorer.title(row.search_name) + bonus)
        }
        val series = seriesRows.chunked(CHUNK).flatMap { q.seriesByRowids(it).executeAsList() }.map { row ->
            val titleScore = scorer.title(row.search_name)
            val bonus = ((row.rating ?: 0.0).coerceIn(0.0, 10.0) / 2).toInt()
            if (titleScore > 0) SearchHit.SeriesHit(row.toModel(), titleScore + bonus, matchedInPlot = false)
            else SearchHit.SeriesHit(row.toModel(), PLOT_SCORE + bonus, matchedInPlot = true)
        }

        // ---- programmes (TV guide)
        val programs = searchPrograms(sourceId, expr, scorer, now)

        val liveNow = programs.filter { it.status == ProgramStatus.LIVE }.sortedByDescending { it.score }.take(SECTION_LIMIT)
        val upcoming = programs.filter { it.status == ProgramStatus.UPCOMING }
            .sortedWith(compareByDescending<SearchHit.ProgramHit> { it.score >= 50 }.thenBy { it.program.startAt })
            .take(SECTION_LIMIT)
        val replay = programs.filter { it.status == ProgramStatus.REPLAY }
            .sortedWith(compareByDescending<SearchHit.ProgramHit> { it.score >= 50 }.thenByDescending { it.program.startAt })
            .take(SECTION_LIMIT)

        val channelList = channels.values.sortedByDescending { it.score }.take(SECTION_LIMIT)
        val movieList = movies.sortedWith(compareByDescending<SearchHit.MovieHit> { it.score }.thenBy { it.movie.name }).take(GRID_LIMIT)
        val seriesList = series.sortedWith(compareByDescending<SearchHit.SeriesHit> { it.score }.thenBy { it.series.name }).take(GRID_LIMIT)

        val top = (liveNow + upcoming.take(10) + replay.take(10) + channelList + movieList + seriesList)
            .sortedByDescending { it.score }
            .take(TOP_LIMIT)

        return SmartSearchResults(
            query = query, correctedQuery = corrected, terms = tokens.map { QueryParser.stem(it) },
            topHits = top, liveNow = liveNow, upcoming = upcoming, replay = replay,
            channels = channelList, movies = movieList, series = seriesList,
        )
    }

    private fun searchPrograms(sourceId: Long, expr: String, scorer: Scorer, now: Long): List<SearchHit.ProgramHit> {
        val rowids = index.matchPrograms(sourceId, expr, now - GUIDE_PAST, now + GUIDE_FUTURE, now, PROGRAM_LIMIT)
        if (rowids.isEmpty()) return emptyList()
        val programs = rowids.chunked(CHUNK).flatMap { q.programsByRowids(it).executeAsList() }.map { it.toModel() }
        val epgIds = programs.map { it.channelEpgId }.distinct()
        val channelsByEpg: Map<String, List<Channel>> = epgIds.chunked(CHUNK)
            .flatMap { q.channelsByEpgIds(sourceId, it).executeAsList() }
            .map { it.toModel() }
            .groupBy { it.epgId.orEmpty() }

        data class Candidate(val program: EpgProgram, val channel: Channel, val status: ProgramStatus, val score: Int)

        val candidates = programs.mapNotNull { p ->
            val variants = channelsByEpg[p.channelEpgId] ?: return@mapNotNull null
            val status: ProgramStatus
            val channel: Channel
            when {
                p.isLive(now) -> { status = ProgramStatus.LIVE; channel = variants.first() }
                p.startAt >= now -> { status = ProgramStatus.UPCOMING; channel = variants.first() }
                else -> {
                    // Past: only if some variant of the channel keeps an archive long enough.
                    channel = variants.firstOrNull { it.archiveDays > 0 && p.startAt >= now - it.archiveDays * DAY }
                        ?: return@mapNotNull null
                    status = ProgramStatus.REPLAY
                }
            }
            val base = scorer.title(normalizeForSearch(p.title)).takeIf { it > 0 } ?: DESCRIPTION_SCORE
            val boost = when (status) {
                ProgramStatus.LIVE -> 30
                ProgramStatus.UPCOMING -> when {
                    p.startAt - now < 3 * HOUR -> 15
                    p.startAt - now < DAY -> 8
                    else -> 2
                }
                ProgramStatus.REPLAY -> 4
            }
            Candidate(p, channel, status, base + boost)
        }

        // Collapse repeated airings of the same show on the same channel (the same daily news show).
        return candidates
            .groupBy { Triple(it.channel.id, normalizeForSearch(it.program.title), it.status) }
            .values
            .map { group ->
                val pick = when (group.first().status) {
                    ProgramStatus.REPLAY -> group.maxBy { it.program.startAt }
                    else -> group.minBy { it.program.startAt }
                }
                SearchHit.ProgramHit(pick.program, pick.channel, pick.status, pick.score, otherAirings = group.size - 1)
            }
    }

    /** Relevance of a (normalised) title for the query. 0 = matched only elsewhere (plot, description). */
    private class Scorer(private val queryNorm: String, private val tokens: List<String>) {
        private val stems = tokens.map { QueryParser.stem(it) }

        fun title(titleNorm: String): Int {
            if (titleNorm == queryNorm) return 100
            val words = QueryParser.tokenize(titleNorm)
            val joined = words.joinToString(" ")
            if (joined == queryNorm) return 100
            if (joined.startsWith(queryNorm)) return 85
            if (tokens.all { t -> words.any { it == t } }) return 70
            if (tokens.all { t -> words.any { it.startsWith(t) } }) return 55
            if (stems.all { s -> words.any { it.startsWith(s) } }) return 40
            return 0
        }
    }

    private companion object {
        const val KEY_RECENT = "recent_searches"
        const val MAX_RECENT = 12
        const val CATALOG_LIMIT = 1500
        const val PROGRAM_LIMIT = 600
        const val CHUNK = 400
        const val SECTION_LIMIT = 40
        const val GRID_LIMIT = 120
        const val TOP_LIMIT = 8
        const val PLOT_SCORE = 18
        const val DESCRIPTION_SCORE = 20
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val GUIDE_PAST = 7 * DAY
        const val GUIDE_FUTURE = 7 * DAY
    }
}
