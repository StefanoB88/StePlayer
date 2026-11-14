package com.steplayer.shared.search

import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.Series

enum class ProgramStatus { LIVE, UPCOMING, REPLAY }

/** One ranked result of the global search. Higher [score] = more relevant. */
sealed interface SearchHit {
    val score: Int

    data class ChannelHit(val channel: Channel, override val score: Int) : SearchHit
    data class MovieHit(val movie: Movie, override val score: Int) : SearchHit
    data class SeriesHit(val series: Series, override val score: Int, val matchedInPlot: Boolean) : SearchHit
    data class ProgramHit(
        val program: EpgProgram,
        val channel: Channel,
        val status: ProgramStatus,
        override val score: Int,
        /** How many other airings of the same show on the same channel were collapsed. */
        val otherAirings: Int,
    ) : SearchHit
}

data class SmartSearchResults(
    val query: String,
    /** Non-null when the query had typos and was auto-corrected ("Forse cercavi: …"). */
    val correctedQuery: String?,
    /** Normalised terms that matched — UIs may use them for highlighting. */
    val terms: List<String>,
    /** Best mixed results across all sections (max 8). */
    val topHits: List<SearchHit>,
    val liveNow: List<SearchHit.ProgramHit>,
    val upcoming: List<SearchHit.ProgramHit>,
    val replay: List<SearchHit.ProgramHit>,
    val channels: List<SearchHit.ChannelHit>,
    val movies: List<SearchHit.MovieHit>,
    val series: List<SearchHit.SeriesHit>,
) {
    val isEmpty: Boolean
        get() = liveNow.isEmpty() && upcoming.isEmpty() && replay.isEmpty() &&
            channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    val totalCount: Int
        get() = liveNow.size + upcoming.size + replay.size + channels.size + movies.size + series.size

    companion object {
        fun empty(query: String) = SmartSearchResults(
            query, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(),
        )
    }
}
