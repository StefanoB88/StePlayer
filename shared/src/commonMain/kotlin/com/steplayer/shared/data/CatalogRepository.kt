package com.steplayer.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.Category
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Episode
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.MovieDetail
import com.steplayer.shared.model.SearchResults
import com.steplayer.shared.model.Series
import com.steplayer.shared.model.SeriesDetail
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.normalizeForSearch
import com.steplayer.shared.util.runCatchingCancellable
import com.steplayer.shared.util.wrap
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamInfoParser
import com.steplayer.shared.xtream.flexInt
import com.steplayer.shared.xtream.flexString
import com.steplayer.shared.xtream.get
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Special category ids understood by every list query. */
object Categories {
    const val ALL = "__all__"
    const val FAVORITES = "__fav__"
    const val RECENT = "__recent__"
}

class CatalogRepository internal constructor(
    private val db: StePlayerDatabase,
    private val http: HttpClient,
    private val sources: SourceRepository,
    private val defaultUserAgent: String,
) {
    private val q get() = db.schemaQueries

    fun categories(sourceId: Long, type: ContentType): CFlow<List<Category>> =
        q.visibleCategories(sourceId, type.name).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { Category(it.id, it.name, locked = it.locked != 0L) } }.wrap()

    fun allCategories(sourceId: Long, type: ContentType): CFlow<List<Category>> =
        q.allCategoriesWithPrefs(sourceId, type.name).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { Category(it.id, it.name, locked = it.locked != 0L, hidden = it.hidden != 0L) } }.wrap()

    @Throws(Throwable::class)
    suspend fun setCategoryHidden(sourceId: Long, type: ContentType, category: Category, hidden: Boolean) =
        withContext(Dispatchers.IO) {
            q.setCategoryPref(sourceId, type.name, category.id, if (hidden) 1 else 0, if (category.locked) 1 else 0)
        }

    // ---------------- Live ----------------

    fun channels(sourceId: Long, categoryId: String): CFlow<List<Channel>> = when (categoryId) {
        Categories.FAVORITES -> q.favoriteChannels(sourceId).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        Categories.ALL -> q.allChannels(sourceId).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        else -> q.channelsByCategory(sourceId, categoryId).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
    }.wrap()

    @Throws(Throwable::class)
    suspend fun channel(sourceId: Long, id: String): Channel? = withContext(Dispatchers.IO) {
        q.channelById(sourceId, id).executeAsOneOrNull()?.toModel()
    }

    /** Channel by its provider number (remote-control number entry across the whole list). */
    @Throws(Throwable::class)
    suspend fun channelByNumber(sourceId: Long, number: Int): Channel? = withContext(Dispatchers.IO) {
        q.firstChannelByNumber(sourceId, number.toLong()).executeAsOneOrNull()?.toModel()
    }

    // ---------------- Movies ----------------

    fun movies(sourceId: Long, categoryId: String, limit: Long = 5000, offset: Long = 0): CFlow<List<Movie>> = when (categoryId) {
        Categories.FAVORITES -> q.favoriteMovies(sourceId).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        Categories.RECENT -> q.recentMovies(sourceId, limit).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        Categories.ALL -> q.allMovies(sourceId, limit, offset).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        else -> q.moviesByCategory(sourceId, categoryId, limit, offset).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
    }.wrap()

    @Throws(Throwable::class)
    suspend fun movie(sourceId: Long, id: String): Movie? = withContext(Dispatchers.IO) {
        q.movieById(sourceId, id).executeAsOneOrNull()?.toModel()
    }

    /** Rich details (plot, cast, backdrop...). Network call for Xtream; local-only for M3U. */
    @Throws(Throwable::class)
    suspend fun movieDetail(movie: Movie): MovieDetail {
        val basic = MovieDetail(movie, null, null, null, null, null, null, null, null)
        val source = sources.byId(movie.sourceId) ?: return basic
        val creds = sources.xtreamCredentials(source) ?: return basic
        val root = runCatchingCancellable {
            XtreamClient(http, creds, source.userAgent ?: defaultUserAgent).vodInfo(movie.id)
        }.getOrNull() ?: return basic
        val info = root["info"]
        return MovieDetail(
            movie = movie.copy(
                poster = movie.poster ?: info["movie_image"].flexString(),
                extension = movie.extension ?: root["movie_data"]["container_extension"].flexString(),
            ),
            plot = info["plot"].flexString() ?: info["description"].flexString(),
            genre = info["genre"].flexString(),
            cast = info["cast"].flexString() ?: info["actors"].flexString(),
            director = info["director"].flexString(),
            releaseDate = info["releasedate"].flexString() ?: info["release_date"].flexString(),
            durationSec = XtreamInfoParser.durationSec(info),
            backdrop = XtreamInfoParser.backdrop(info),
            trailerYoutubeId = info["youtube_trailer"].flexString(),
        )
    }

    // ---------------- Series ----------------

    fun series(sourceId: Long, categoryId: String, limit: Long = 5000, offset: Long = 0): CFlow<List<Series>> = when (categoryId) {
        Categories.FAVORITES -> q.favoriteSeries(sourceId).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        Categories.RECENT -> q.recentSeries(sourceId, limit).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        Categories.ALL -> q.allSeries(sourceId, limit, offset).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
        else -> q.seriesByCategory(sourceId, categoryId, limit, offset).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }
    }.wrap()

    @Throws(Throwable::class)
    suspend fun seriesById(sourceId: Long, id: String): Series? = withContext(Dispatchers.IO) {
        q.seriesById(sourceId, id).executeAsOneOrNull()?.toModel()
    }

    /**
     * Seasons and episodes. For Xtream it fetches get_series_info and caches episodes locally,
     * so the detail page opens instantly next time and works offline for downloaded episodes.
     */
    @Throws(Throwable::class)
    suspend fun seriesDetail(series: Series): SeriesDetail {
        val cached = withContext(Dispatchers.IO) {
            q.episodesOfSeries(series.sourceId, series.id).executeAsList().map { it.toModel() }
        }
        val source = sources.byId(series.sourceId)
        val creds = source?.let { sources.xtreamCredentials(it) }
        if (creds == null) return SeriesDetail(series, null, null, null, cached.seasons(), cached)

        val root = runCatchingCancellable {
            XtreamClient(http, creds, source.userAgent ?: defaultUserAgent).seriesInfo(series.id)
        }.getOrNull() ?: return SeriesDetail(series, null, null, null, cached.seasons(), cached)

        val info = root["info"]
        val episodes = XtreamInfoParser.episodes(root).mapNotNull { e ->
            val id = e["id"].flexString() ?: return@mapNotNull null
            val epInfo = e["info"]
            Episode(
                sourceId = series.sourceId,
                seriesId = series.id,
                id = id,
                season = e["season"].flexInt() ?: 1,
                number = e["episode_num"].flexInt() ?: 0,
                title = e["title"].flexString() ?: "Episodio",
                plot = epInfo["plot"].flexString(),
                image = epInfo["movie_image"].flexString() ?: epInfo["cover_big"].flexString(),
                durationSec = XtreamInfoParser.durationSec(epInfo),
                extension = e["container_extension"].flexString(),
            )
        }.sortedWith(compareBy({ it.season }, { it.number }))

        withContext(Dispatchers.IO) {
            db.transaction {
                q.deleteEpisodesOfSeries(series.sourceId, series.id)
                episodes.forEach { ep ->
                    q.insertEpisode(
                        ep.sourceId, ep.seriesId, ep.id, ep.season.toLong(), ep.number.toLong(), ep.title,
                        ep.plot, ep.image, ep.durationSec?.toLong(), ep.extension, null,
                    )
                }
            }
        }
        val enriched = series.copy(
            plot = series.plot ?: info["plot"].flexString(),
            cover = series.cover ?: info["cover"].flexString(),
            genre = series.genre ?: info["genre"].flexString(),
        )
        return SeriesDetail(
            series = enriched,
            backdrop = XtreamInfoParser.backdrop(info),
            cast = info["cast"].flexString(),
            director = info["director"].flexString(),
            seasons = episodes.seasons(),
            episodes = episodes,
        )
    }

    @Throws(Throwable::class)
    suspend fun episode(sourceId: Long, id: String): Episode? = withContext(Dispatchers.IO) {
        q.episodeById(sourceId, id).executeAsOneOrNull()?.toModel()
    }

    // ---------------- Search ----------------

    @Throws(Throwable::class)
    suspend fun search(sourceId: Long, query: String, limit: Long = 60): SearchResults {
        val term = normalizeForSearch(query)
        if (term.length < 2) return SearchResults(emptyList(), emptyList(), emptyList())
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val ch = async { q.searchChannels(sourceId, term, limit).executeAsList().map { it.toModel() } }
                val mv = async { q.searchMovies(sourceId, term, limit).executeAsList().map { it.toModel() } }
                val se = async { q.searchSeries(sourceId, term, limit).executeAsList().map { it.toModel() } }
                SearchResults(ch.await(), mv.await(), se.await())
            }
        }
    }

    private fun List<Episode>.seasons() = map { it.season }.distinct().sorted()
}
