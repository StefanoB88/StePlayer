package com.steplayer.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus
import com.steplayer.shared.model.Episode
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.Series
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.logCore
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What a platform download engine needs to fetch one file. */
data class DownloadRequest(
    val id: String,
    val url: String,
    val headers: Map<String, String>,
    /** File name (no directory) inside PlatformDependencies.downloadsDirectory. */
    val fileName: String,
    val title: String,
)

/**
 * Implemented natively: Android uses WorkManager + foreground notification,
 * iOS uses a background URLSession so downloads continue with the app closed.
 */
interface DownloadEngine {
    fun start(request: DownloadRequest)
    fun pause(id: String)
    fun cancel(id: String)
}

class DownloadRepository internal constructor(
    private val db: StePlayerDatabase,
    private val streams: StreamResolver,
) {
    private val q get() = db.schemaQueries

    /** Set by the platform at startup. */
    var engine: DownloadEngine? = null

    val downloads: CFlow<List<Download>> =
        q.allDownloads().asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }.wrap()

    /** Emits a list with 0 or 1 element (CFlow can't carry nulls to Swift). */
    fun observe(id: String): CFlow<List<Download>> =
        q.downloadById(id).asFlow().mapToOneOrNull(Dispatchers.IO).map { listOfNotNull(it?.toModel()) }.wrap()

    @Throws(Throwable::class)
    suspend fun downloadMovie(movie: Movie): String {
        val id = idFor(movie.sourceId, ContentType.MOVIE.name, movie.id)
        val stream = streams.movie(movie)
        return enqueue(id, movie.sourceId, ContentType.MOVIE, movie.id, null, movie.name, movie.year?.toString(), movie.poster,
            stream.container ?: movie.extension, stream.url, stream.headers)
    }

    @Throws(Throwable::class)
    suspend fun downloadEpisode(series: Series, episode: Episode): String {
        val id = idFor(episode.sourceId, ContentType.EPISODE.name, episode.id)
        val stream = streams.episode(episode)
        val subtitle = "S${episode.season.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')} · ${episode.title}"
        return enqueue(id, episode.sourceId, ContentType.EPISODE, episode.id, series.id, series.name, subtitle,
            episode.image ?: series.cover, stream.container ?: episode.extension, stream.url, stream.headers)
    }

    private suspend fun enqueue(
        id: String, sourceId: Long, type: ContentType, itemId: String, parentId: String?, title: String,
        subtitle: String?, image: String?, ext: String?, url: String, headers: Map<String, String>,
    ): String {
        val existing = withContext(Dispatchers.IO) { q.downloadById(id).executeAsOneOrNull() }
        if (existing?.status == DownloadStatus.COMPLETED.name) return id
        if (url.startsWith("file://")) return id
        val fileName = "${id.replace(':', '_')}.${ext ?: "mp4"}"
        withContext(Dispatchers.IO) {
            val now = nowMillis()
            q.insertDownload(
                id, sourceId, type.name, itemId, parentId, title, subtitle, image, ext,
                DownloadStatus.QUEUED.name, fileName, existing?.bytes_downloaded ?: 0, existing?.total_bytes ?: 0,
                null, existing?.created_at ?: now, now,
            )
        }
        engine?.start(DownloadRequest(id, url, headers, fileName, title))
        return id
    }

    /** Re-issues a request for a paused/failed download (URL rebuilt with fresh credentials). */
    @Throws(Throwable::class)
    suspend fun resume(id: String) {
        val row = withContext(Dispatchers.IO) { q.downloadById(id).executeAsOneOrNull() } ?: return
        val stream = when (row.type) {
            ContentType.MOVIE.name -> streams.movie(Movie(row.source_id, row.item_id, row.title, row.image, null, null, null, row.extension, null))
            else -> streams.episode(Episode(row.source_id, row.parent_id.orEmpty(), row.item_id, 0, 0, row.title, null, null, null, row.extension))
        }
        if (stream.url.startsWith("file://")) return
        withContext(Dispatchers.IO) { q.updateDownloadStatus(DownloadStatus.QUEUED.name, null, nowMillis(), id) }
        engine?.start(DownloadRequest(id, stream.url, stream.headers, row.file_path ?: "${id.replace(':', '_')}.mp4", row.title))
    }

    @Throws(Throwable::class)
    suspend fun pause(id: String) {
        engine?.pause(id)
        withContext(Dispatchers.IO) { q.updateDownloadStatus(DownloadStatus.PAUSED.name, null, nowMillis(), id) }
    }

    /** Pauses every queued/running download (e.g. to free the connection for playback). */
    @Throws(Throwable::class)
    suspend fun pauseAll() {
        pending().forEach { pause(it.id) }
    }

    /** Cancels and deletes the record. The platform engine deletes the file. */
    @Throws(Throwable::class)
    suspend fun delete(id: String) {
        engine?.cancel(id)
        withContext(Dispatchers.IO) { q.deleteDownload(id) }
    }

    /** Ids of downloads that should be (re)started, e.g. after app restart. */
    @Throws(Throwable::class)
    suspend fun pending(): List<Download> = withContext(Dispatchers.IO) {
        q.downloadsByStatus(listOf(DownloadStatus.QUEUED.name, DownloadStatus.RUNNING.name)).executeAsList().map { it.toModel() }
    }

    // ---- callbacks from the platform engine ----
    // Called synchronously from native download code (URLSession delegate / WorkManager): a DB
    // error here must never propagate into Swift (no @Throws → crash) or kill the worker.

    fun onProgress(id: String, bytes: Long, total: Long) = guarded("onProgress") {
        q.updateDownloadProgress(DownloadStatus.RUNNING.name, bytes, total, nowMillis(), id)
    }

    fun onCompleted(id: String, fileName: String) = guarded("onCompleted") {
        q.completeDownload(fileName, nowMillis(), id)
    }

    /** Waiting for network or a free connection: not running, not failed. Keeps the progress. */
    fun onQueued(id: String) = guarded("onQueued") {
        q.updateDownloadStatus(DownloadStatus.QUEUED.name, null, nowMillis(), id)
    }

    fun onFailed(id: String, message: String?) = guarded("onFailed") {
        q.updateDownloadStatus(DownloadStatus.FAILED.name, message, nowMillis(), id)
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logCore("DownloadRepository.$what failed", e)
        }
    }

    companion object {
        fun idFor(sourceId: Long, type: String, itemId: String) = "$sourceId:$type:$itemId"
    }
}
