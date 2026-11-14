package com.steplayer.shared.data

import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.DownloadStatus
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.model.Episode
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.PlayableStream
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.util.CivilTime
import com.steplayer.shared.util.pad2
import com.steplayer.shared.xtream.XtreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class StreamException(message: String) : Exception(message)

/** Upper bound for a catch-up request (7 days): protects the URL math from garbage EPG times. */
private const val MAX_CATCHUP_MINUTES = 7 * 24 * 60L

/**
 * Builds playable URLs at the last moment, so passwords never need to be persisted in URLs.
 * Downloaded files always win over network streams.
 */
class StreamResolver internal constructor(
    private val db: StePlayerDatabase,
    private val sources: SourceRepository,
    private val downloadsDirectory: String,
    private val prefersHlsLive: Boolean,
    private val defaultUserAgent: String,
) {
    private val q get() = db.schemaQueries

    private suspend fun source(id: Long) = sources.byId(id) ?: throw StreamException("Lista non trovata")

    private fun headers(source: Source, override: String? = null) =
        mapOf("User-Agent" to (override ?: source.userAgent ?: defaultUserAgent))

    @Throws(Throwable::class)
    suspend fun live(channel: Channel): PlayableStream {
        val source = source(channel.sourceId)
        return when (source.type) {
            SourceType.XTREAM -> {
                val creds = sources.xtreamCredentials(source) ?: throw StreamException("Credenziali mancanti")
                val formats = source.allowedFormats
                val ext = when {
                    prefersHlsLive && (formats.isEmpty() || "m3u8" in formats) -> "m3u8"
                    !prefersHlsLive && (formats.isEmpty() || "ts" in formats) -> "ts"
                    "m3u8" in formats -> "m3u8"
                    else -> "ts"
                }
                PlayableStream(XtreamUrls.live(creds, channel.id, ext), headers(source), isLive = true, container = ext)
            }
            SourceType.M3U -> {
                val url = m3uUrl(channel.sourceId, channel.id)
                PlayableStream(url, headers(source), isLive = true, container = containerOf(url))
            }
        }
    }

    /** Catch-up playback of a past programme. */
    @Throws(Throwable::class)
    suspend fun catchup(channel: Channel, program: EpgProgram): PlayableStream {
        if (channel.archiveDays <= 0) throw StreamException("Questo canale non supporta il replay")
        val source = source(channel.sourceId)
        // Clamped in Long before narrowing: a garbage EPG end time must not wrap around Int.
        val durationMin = ((program.endAt - program.startAt) / 60_000).coerceIn(1L, MAX_CATCHUP_MINUTES).toInt()
        return when (source.type) {
            SourceType.XTREAM -> {
                val creds = sources.xtreamCredentials(source) ?: throw StreamException("Credenziali mancanti")
                val url = XtreamUrls.timeshift(creds, channel.id, program.startAt, durationMin, source.serverTimeOffsetSec)
                PlayableStream(url, headers(source), isLive = false, container = "ts")
            }
            SourceType.M3U -> {
                val row = withContext(Dispatchers.IO) { q.channelById(channel.sourceId, channel.id).executeAsOneOrNull() }
                    ?: throw StreamException("Canale non trovato")
                val base = row.stream_url ?: throw StreamException("Canale non trovato")
                val url = CatchupTemplates.build(base, row.catchup_source, program.startAt, program.endAt)
                    ?: throw StreamException("Replay non disponibile per questo canale")
                PlayableStream(url, headers(source), isLive = false, container = containerOf(url))
            }
        }
    }

    @Throws(Throwable::class)
    suspend fun movie(movie: Movie): PlayableStream {
        localFile(movie.sourceId, "MOVIE", movie.id)?.let { return it }
        val source = source(movie.sourceId)
        val url = when (source.type) {
            SourceType.XTREAM -> XtreamUrls.movie(
                sources.xtreamCredentials(source) ?: throw StreamException("Credenziali mancanti"), movie.id, movie.extension,
            )
            SourceType.M3U -> withContext(Dispatchers.IO) { q.movieById(movie.sourceId, movie.id).executeAsOneOrNull()?.stream_url }
                ?: throw StreamException("Film non trovato")
        }
        return PlayableStream(url, headers(source), isLive = false, container = movie.extension ?: containerOf(url))
    }

    @Throws(Throwable::class)
    suspend fun episode(episode: Episode): PlayableStream {
        localFile(episode.sourceId, "EPISODE", episode.id)?.let { return it }
        val source = source(episode.sourceId)
        val url = when (source.type) {
            SourceType.XTREAM -> XtreamUrls.episode(
                sources.xtreamCredentials(source) ?: throw StreamException("Credenziali mancanti"), episode.id, episode.extension,
            )
            SourceType.M3U -> withContext(Dispatchers.IO) { q.episodeById(episode.sourceId, episode.id).executeAsOneOrNull()?.stream_url }
                ?: throw StreamException("Episodio non trovato")
        }
        return PlayableStream(url, headers(source), isLive = false, container = episode.extension ?: containerOf(url))
    }

    private suspend fun m3uUrl(sourceId: Long, channelId: String): String =
        withContext(Dispatchers.IO) { q.channelById(sourceId, channelId).executeAsOneOrNull()?.stream_url }
            ?: throw StreamException("Canale non trovato")

    private suspend fun localFile(sourceId: Long, type: String, itemId: String): PlayableStream? {
        val row = withContext(Dispatchers.IO) { q.downloadById(DownloadRepository.idFor(sourceId, type, itemId)).executeAsOneOrNull() }
            ?: return null
        if (row.status != DownloadStatus.COMPLETED.name) return null
        val name = row.file_path ?: return null
        return PlayableStream("file://$downloadsDirectory/$name", emptyMap(), isLive = false, container = row.extension)
    }

    private fun containerOf(url: String): String? =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }
}

/** Implements the common M3U catch-up conventions (Kodi/TiviMate style). */
object CatchupTemplates {
    fun build(streamUrl: String, encoded: String?, startMs: Long, endMs: Long): String? {
        val mode = encoded?.substringBefore('|')?.lowercase() ?: "default"
        val template = encoded?.substringAfter('|', "")?.takeIf { it.isNotBlank() }
        return when (mode) {
            "append" -> template?.let { streamUrl + fill(it, startMs, endMs) }
            "shift", "timeshift" -> {
                val sep = if ('?' in streamUrl) "&" else "?"
                "$streamUrl${sep}utc=${startMs / 1000}&lutc=${nowSec()}"
            }
            "flussonic", "flussonic-hls", "fs" -> {
                val start = startMs / 1000
                val dur = (endMs - startMs) / 1000
                when {
                    streamUrl.contains("/index.m3u8") -> streamUrl.replace("/index.m3u8", "/index-$start-$dur.m3u8")
                    streamUrl.contains("/mpegts") -> streamUrl.replace("/mpegts", "/timeshift_abs-$start.ts")
                    else -> null
                }
            }
            else -> template?.let { t ->
                val filled = fill(t, startMs, endMs)
                if (filled.contains("://")) filled else streamUrl + filled
            }
        }
    }

    private fun nowSec() = com.steplayer.shared.util.nowMillis() / 1000

    internal fun fill(template: String, startMs: Long, endMs: Long): String {
        val start = startMs / 1000
        val end = endMs / 1000
        val dur = end - start
        val t = CivilTime.fromEpochSeconds(start)
        val e = CivilTime.fromEpochSeconds(end)
        var s = template
        val map = linkedMapOf(
            "\${start}" to start.toString(), "{start}" to start.toString(), "{utc}" to start.toString(),
            "\${end}" to end.toString(), "{end}" to end.toString(), "{utcend}" to end.toString(),
            "\${timestamp}" to nowSec().toString(), "{lutc}" to nowSec().toString(), "\${now}" to nowSec().toString(),
            "\${duration}" to dur.toString(), "{duration}" to dur.toString(),
            "\${offset}" to (nowSec() - start).toString(), "{offset}" to (nowSec() - start).toString(),
            "{Y}" to t.year.toString(), "{m}" to t.month.pad2(), "{d}" to t.day.pad2(),
            "{H}" to t.hour.pad2(), "{M}" to t.minute.pad2(), "{S}" to t.second.pad2(),
            "{(b)YmdHMS}" to "${t.year}${t.month.pad2()}${t.day.pad2()}${t.hour.pad2()}${t.minute.pad2()}${t.second.pad2()}",
            "{(e)YmdHMS}" to "${e.year}${e.month.pad2()}${e.day.pad2()}${e.hour.pad2()}${e.minute.pad2()}${e.second.pad2()}",
        )
        map.forEach { (k, v) -> s = s.replace(k, v) }
        // {duration:60} → minutes
        s = Regex("\\{duration:(\\d+)\\}").replace(s) { m -> (dur / (m.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString() }
        return s
    }
}
