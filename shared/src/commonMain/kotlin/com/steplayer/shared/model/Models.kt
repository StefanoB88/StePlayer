package com.steplayer.shared.model

enum class SourceType { XTREAM, M3U }

/** Type of a catalog item. Stored as text in the database. */
enum class ContentType { LIVE, MOVIE, SERIES, EPISODE }

data class Source(
    val id: Long,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String?,
    val epgUrl: String?,
    val userAgent: String?,
    val serverTimeOffsetSec: Long,
    val allowedFormats: List<String>,
    val expiresAt: Long?,
    val maxConnections: Int?,
    val lastSyncAt: Long?,
    val lastEpgSyncAt: Long?,
)

/** What the user types in the "add list" form. */
sealed interface SourceInput {
    val name: String

    data class Xtream(
        override val name: String,
        val serverUrl: String,
        val username: String,
        val password: String,
        val userAgent: String? = null,
    ) : SourceInput

    data class M3u(
        override val name: String,
        val listUrl: String,
        val epgUrl: String? = null,
        val userAgent: String? = null,
    ) : SourceInput
}

data class Category(
    val id: String,
    val name: String,
    val locked: Boolean = false,
    val hidden: Boolean = false,
)

data class Channel(
    val sourceId: Long,
    val id: String,
    val num: Int?,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val epgId: String?,
    val archiveDays: Int,
)

data class Movie(
    val sourceId: Long,
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: Double?,
    val year: Int?,
    val extension: String?,
    val addedAt: Long?,
)

data class MovieDetail(
    val movie: Movie,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val durationSec: Int?,
    val backdrop: String?,
    val trailerYoutubeId: String?,
)

data class Series(
    val sourceId: Long,
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: Double?,
    val year: Int?,
    val genre: String?,
)

data class Episode(
    val sourceId: Long,
    val seriesId: String,
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSec: Int?,
    val extension: String?,
)

data class SeriesDetail(
    val series: Series,
    val backdrop: String?,
    val cast: String?,
    val director: String?,
    val seasons: List<Int>,
    val episodes: List<Episode>,
) {
    fun episodesOf(season: Int): List<Episode> = episodes.filter { it.season == season }
}

data class EpgProgram(
    val channelEpgId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String?,
) {
    fun isLive(now: Long): Boolean = now in startAt until endAt
    fun progress(now: Long): Float =
        if (endAt <= startAt) 0f else ((now - startAt).toFloat() / (endAt - startAt)).coerceIn(0f, 1f)
}

data class ChannelWithEpg(
    val channel: Channel,
    val now: EpgProgram?,
    val next: EpgProgram?,
)

data class WatchProgress(
    val sourceId: Long,
    val type: ContentType,
    val itemId: String,
    val parentId: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

data class Download(
    val id: String,
    val sourceId: Long,
    val type: ContentType,
    val itemId: String,
    val parentId: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val extension: String?,
    val status: DownloadStatus,
    val filePath: String?,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val error: String?,
    val createdAt: Long,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Account information reported by an Xtream server. */
data class AccountInfo(
    val status: String?,
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val allowedFormats: List<String>,
)

/** A playable stream: URL plus the HTTP headers required to open it. */
data class PlayableStream(
    val url: String,
    val headers: Map<String, String>,
    val isLive: Boolean,
    /** Container hint, e.g. "m3u8", "ts", "mp4", "mkv". */
    val container: String?,
)

data class SearchResults(
    val channels: List<Channel>,
    val movies: List<Movie>,
    val series: List<Series>,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val step: SyncStep, val progress: Float) : SyncState
    data class Failed(val message: String) : SyncState
    data object Done : SyncState
}

enum class SyncStep { AUTHENTICATING, LIVE, MOVIES, SERIES, EPG, SAVING }
