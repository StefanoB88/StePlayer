package com.steplayer.shared.data

import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.m3u.M3uClassifier
import com.steplayer.shared.m3u.M3uEntry
import com.steplayer.shared.m3u.M3uKind
import com.steplayer.shared.m3u.M3uParser
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.model.SyncState
import com.steplayer.shared.model.SyncStep
import com.steplayer.shared.search.SearchIndex
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.extractYear
import com.steplayer.shared.util.normalizeForSearch
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.runCatchingCancellable
import com.steplayer.shared.util.stableHash
import com.steplayer.shared.util.wrap
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.net.NetworkErrors
import com.steplayer.shared.xtream.XtreamException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Downloads a list's catalog and replaces the local copy atomically per section.
 * Favorites, progress and downloads live in separate tables, so they survive a resync.
 */
class SyncManager internal constructor(
    private val db: StePlayerDatabase,
    private val http: HttpClient,
    private val sources: SourceRepository,
    private val epg: EpgRepository,
    private val defaultUserAgent: String,
    private val searchIndex: SearchIndex,
) {
    private val q get() = db.schemaQueries
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: CFlow<SyncState> = _state.asStateFlow().wrap()

    /** Syncs only if the catalog is older than [maxAgeMs] (default 12h). Safe to call on every app start. */
    @Throws(Throwable::class)
    suspend fun syncIfStale(sourceId: Long, maxAgeMs: Long = 12 * 60 * 60 * 1000L) {
        val source = sources.byId(sourceId) ?: return
        val last = source.lastSyncAt ?: 0
        if (nowMillis() - last > maxAgeMs) sync(sourceId)
        else if (nowMillis() - (source.lastEpgSyncAt ?: 0) > maxAgeMs) runCatchingCancellable { epg.refresh(source) }
    }

    /** Full sync. Throws on failure (also reflected in [state]). */
    @Throws(Throwable::class)
    suspend fun sync(sourceId: Long) = mutex.withLock {
        val source = sources.byId(sourceId) ?: return@withLock
        try {
            when (source.type) {
                SourceType.XTREAM -> syncXtream(source)
                SourceType.M3U -> syncM3u(source)
            }
            _state.value = SyncState.Running(SyncStep.SAVING, 0.88f)
            withContext(Dispatchers.IO) {
                runCatching { searchIndex.rebuildCatalog(source.id) } // search must never break sync
                q.markSourceSynced(nowMillis(), source.id)
            }
            _state.value = SyncState.Running(SyncStep.EPG, 0.92f)
            // EPG failures must never fail the catalog sync.
            runCatchingCancellable { epg.refresh(sources.byId(sourceId) ?: source) }
            _state.value = SyncState.Done
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Throwable) {
            _state.value = SyncState.Failed(e.message ?: "Sincronizzazione non riuscita")
            throw e
        }
    }

    private suspend fun syncXtream(source: Source) = coroutineScope {
        val creds = sources.xtreamCredentials(source)
            ?: throw XtreamException("Username e password mancanti: modifica la lista e inseriscili.", XtreamException.Kind.INVALID_CREDENTIALS)
        val client = XtreamClient(http, creds, source.userAgent ?: defaultUserAgent)

        _state.value = SyncState.Running(SyncStep.AUTHENTICATING, 0.02f)
        val login = client.login()
        withContext(Dispatchers.IO) {
            q.updateSourceAccountInfo(
                login.serverOffsetSec,
                login.account.allowedFormats.joinToString(","),
                login.account.expiresAt,
                login.account.maxConnections?.toLong(),
                source.id,
            )
        }

        // Fetch in parallel, persist sequentially (SQLite has a single writer anyway).
        val liveCats = async { client.liveCategories() }
        val vodCats = async { client.vodCategories() }
        val seriesCats = async { client.seriesCategories() }
        val live = async { client.liveStreams() }
        val vod = async { client.vodStreams() }
        val series = async { client.series() }

        _state.value = SyncState.Running(SyncStep.LIVE, 0.1f)
        val liveList = live.await()
        val liveCatList = liveCats.await()
        withContext(Dispatchers.IO) {
            db.transaction {
                q.deleteCategories(source.id, ContentType.LIVE.name)
                liveCatList.forEachIndexed { i, c ->
                    q.insertCategory(source.id, ContentType.LIVE.name, c.categoryId ?: return@forEachIndexed, c.categoryName ?: "—", i.toLong())
                }
                q.deleteChannels(source.id)
                liveList.forEachIndexed { i, s ->
                    report(SyncStep.LIVE, 0.1f, 0.35f, i, liveList.size)
                    val id = s.streamId ?: return@forEachIndexed
                    val name = s.name?.trim().orEmpty().ifEmpty { "Canale $id" }
                    q.insertChannel(
                        source_id = source.id, id = id, num = s.num?.toLong(), name = name,
                        search_name = normalizeForSearch(name), logo = s.streamIcon, category_id = s.categoryId,
                        epg_id = s.epgChannelId, stream_url = null,
                        archive_days = if (s.tvArchive == true) (s.tvArchiveDuration ?: 0).toLong() else 0L,
                        catchup_source = null, position = i.toLong(),
                    )
                }
            }
        }

        _state.value = SyncState.Running(SyncStep.MOVIES, 0.35f)
        val vodList = vod.await()
        val vodCatList = vodCats.await()
        withContext(Dispatchers.IO) {
            db.transaction {
                q.deleteCategories(source.id, ContentType.MOVIE.name)
                vodCatList.forEachIndexed { i, c ->
                    q.insertCategory(source.id, ContentType.MOVIE.name, c.categoryId ?: return@forEachIndexed, c.categoryName ?: "—", i.toLong())
                }
                q.deleteMovies(source.id)
                vodList.forEachIndexed { i, m ->
                    report(SyncStep.MOVIES, 0.35f, 0.6f, i, vodList.size)
                    val id = m.streamId ?: return@forEachIndexed
                    val name = m.name?.trim().orEmpty().ifEmpty { "Film $id" }
                    q.insertMovie(
                        source_id = source.id, id = id, name = name, search_name = normalizeForSearch(name),
                        poster = m.streamIcon, category_id = m.categoryId, rating = m.rating?.takeIf { it > 0 },
                        year = (extractYear(m.year) ?: extractYear(name))?.toLong(), extension = m.containerExtension,
                        stream_url = null, added_at = m.added?.times(1000), position = i.toLong(),
                    )
                }
            }
        }

        _state.value = SyncState.Running(SyncStep.SERIES, 0.6f)
        val seriesList = series.await()
        val seriesCatList = seriesCats.await()
        withContext(Dispatchers.IO) {
            db.transaction {
                q.deleteCategories(source.id, ContentType.SERIES.name)
                seriesCatList.forEachIndexed { i, c ->
                    q.insertCategory(source.id, ContentType.SERIES.name, c.categoryId ?: return@forEachIndexed, c.categoryName ?: "—", i.toLong())
                }
                q.deleteSeries(source.id)
                seriesList.forEachIndexed { i, s ->
                    report(SyncStep.SERIES, 0.6f, 0.85f, i, seriesList.size)
                    val id = s.seriesId ?: return@forEachIndexed
                    val name = s.name?.trim().orEmpty().ifEmpty { "Serie $id" }
                    q.insertSeries(
                        source_id = source.id, id = id, name = name, search_name = normalizeForSearch(name),
                        cover = s.cover, category_id = s.categoryId, plot = s.plot, rating = s.rating?.takeIf { it > 0 },
                        year = (extractYear(s.year) ?: extractYear(s.releaseDate ?: s.releaseDate2))?.toLong(),
                        genre = s.genre, last_modified = s.lastModified?.times(1000), position = i.toLong(),
                    )
                }
            }
        }
    }

    private suspend fun syncM3u(source: Source) {
        val url = sources.m3uUrl(source) ?: error("URL della lista mancante")
        _state.value = SyncState.Running(SyncStep.AUTHENTICATING, 0.02f)

        val sink = M3uSink(source.id)
        var epgUrls: List<String> = emptyList()
        var receiving = false

        try {
        http.prepareGet(url) {
            header(HttpHeaders.UserAgent, source.userAgent ?: defaultUserAgent)
        }.execute { response ->
            if (!response.status.isSuccess()) throw NetworkErrors.fromStatus(response.status.value, NetworkErrors.Target.LIST)
            receiving = true
            _state.value = SyncState.Running(SyncStep.LIVE, 0.1f)
            val total = response.contentLength()?.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            withContext(Dispatchers.IO) {
                // The blocking bridge below runs as a child of this coroutine, so cancelling the
                // sync aborts the download and rolls the transaction back (old data stays).
                val job = coroutineContext[Job] ?: EmptyCoroutineContext
                db.transaction {
                    sink.clear()
                    val parser = M3uParser { entry -> sink.add(entry) }
                    // Parsing inside the transaction keeps inserts fast (single fsync).
                    // Runs on Dispatchers.IO (never on a main thread).
                    kotlinx.coroutines.runBlocking(job) {
                        var read = 0L
                        var lines = 0
                        while (true) {
                            val line = channel.readUTF8Line() ?: break
                            parser.feed(line)
                            read += line.length + 1
                            if (++lines % 2_000 == 0) {
                                // Size known: real fraction; unknown: creeps towards the end.
                                val f = if (total != null) (read.toFloat() / total).coerceAtMost(1f) else 1f - 1f / (1f + lines / 40_000f)
                                _state.value = SyncState.Running(SyncStep.LIVE, 0.1f + 0.75f * f)
                            }
                        }
                    }
                    sink.finish()
                    epgUrls = parser.header.epgUrls
                }
            }
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: XtreamException) {
            throw e
        } catch (e: Throwable) {
            // Network failures (also while reading the list) get a precise message; anything
            // else after the download started (e.g. storage) keeps its own.
            if (!receiving || NetworkErrors.causeOf(e) != null) throw NetworkErrors.fromThrowable(e, url, NetworkErrors.Target.LIST)
            throw e
        }
        if (epgUrls.isNotEmpty()) {
            withContext(Dispatchers.IO) { q.updateSourceEpgUrl(epgUrls.first(), source.id) }
        }
    }

    /** Progress inside a step, from [from] to [to], updated every 500 items. */
    private fun report(step: SyncStep, from: Float, to: Float, index: Int, size: Int) {
        if (index % 500 == 0 && size > 0) _state.value = SyncState.Running(step, from + (to - from) * index / size)
    }

    /** Streams parsed M3U entries straight into the database. Must run inside a transaction. */
    private inner class M3uSink(private val sourceId: Long) {
        private val categories = HashMap<String, MutableMap<String, Int>>() // type -> (name -> position)
        private val seriesIds = HashMap<String, String>() // normalized show -> id
        private val usedIds = HashMap<String, Int>()     // id -> times seen (duplicates in the same list)
        private var livePos = 0L
        private var moviePos = 0L
        private var seriesPos = 0L

        fun clear() {
            q.deleteCategories(sourceId, ContentType.LIVE.name)
            q.deleteCategories(sourceId, ContentType.MOVIE.name)
            q.deleteCategories(sourceId, ContentType.SERIES.name)
            q.deleteChannels(sourceId)
            q.deleteMovies(sourceId)
            q.deleteSeries(sourceId)
            q.deleteEpisodes(sourceId)
        }

        private fun categoryId(type: ContentType, group: String?): String {
            val name = group?.trim()?.takeIf { it.isNotEmpty() } ?: "Senza categoria"
            val map = categories.getOrPut(type.name) { LinkedHashMap() }
            val id = stableHash(name)
            if (name !in map) {
                val position = map.size
                map[name] = position
                q.insertCategory(sourceId, type.name, id, name, position.toLong())
            }
            return id
        }

        fun add(e: M3uEntry) {
            // Stable across syncs, and unique even when the provider lists the same stream URL
            // several times (same channel in more groups, or one URL reused for many entries).
            val base = stableHash("${e.url}|${e.displayName}|${e.group.orEmpty()}")
            val seen = usedIds[base] ?: 0
            usedIds[base] = seen + 1
            val id = if (seen == 0) base else "$base-$seen"
            when (M3uClassifier.classify(e)) {
                M3uKind.LIVE -> {
                    val name = e.displayName
                    q.insertChannel(
                        source_id = sourceId, id = id, num = e.channelNumber?.toLong(), name = name,
                        search_name = normalizeForSearch(name), logo = e.logo,
                        category_id = categoryId(ContentType.LIVE, e.group), epg_id = e.tvgId ?: e.tvgName,
                        stream_url = e.url, archive_days = e.catchupDays.toLong(),
                        catchup_source = encodeCatchup(e), position = livePos++,
                    )
                }
                M3uKind.MOVIE -> {
                    val name = e.displayName
                    q.insertMovie(
                        source_id = sourceId, id = id, name = name, search_name = normalizeForSearch(name),
                        poster = e.logo, category_id = categoryId(ContentType.MOVIE, e.group), rating = null,
                        year = extractYear(name)?.toLong(), extension = extensionOf(e.url), stream_url = e.url,
                        added_at = null, position = moviePos++,
                    )
                }
                M3uKind.SERIES -> {
                    val ref = M3uClassifier.parseEpisode(e.displayName)
                    val show = ref?.showName ?: e.displayName
                    val key = normalizeForSearch(show)
                    val seriesId = seriesIds.getOrPut(key) {
                        val sid = stableHash("series:$key")
                        q.insertSeries(
                            source_id = sourceId, id = sid, name = show, search_name = key, cover = e.logo,
                            category_id = categoryId(ContentType.SERIES, e.group), plot = null, rating = null,
                            year = extractYear(show)?.toLong(), genre = null, last_modified = null, position = seriesPos++,
                        )
                        sid
                    }
                    q.insertEpisode(
                        source_id = sourceId, series_id = seriesId, id = id,
                        season = (ref?.season ?: 1).toLong(), number = (ref?.episode ?: 0).toLong(),
                        title = e.displayName, plot = null, image = e.logo, duration_sec = null,
                        extension = extensionOf(e.url), stream_url = e.url,
                    )
                }
            }
        }

        fun finish() = Unit
    }

    private fun extensionOf(url: String): String? =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }

    /** Persists catch-up mode and template as "mode|template". */
    private fun encodeCatchup(e: M3uEntry): String? {
        val mode = e.catchupMode ?: if (e.catchupSource != null) "default" else return null
        return "$mode|${e.catchupSource.orEmpty()}"
    }
}
