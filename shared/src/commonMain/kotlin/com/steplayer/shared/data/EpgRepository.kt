package com.steplayer.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.epg.TextChunkDecoder
import com.steplayer.shared.epg.XmltvParser
import com.steplayer.shared.epg.XmltvProgramme
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ChannelWithEpg
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.search.SearchIndex
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.runCatchingCancellable
import com.steplayer.shared.util.wrap
import com.steplayer.shared.util.wrapOr
import com.steplayer.shared.net.NetworkErrors
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamException
import kotlinx.coroutines.CancellationException
import com.steplayer.shared.xtream.XtreamUrls
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class EpgRepository internal constructor(
    private val db: StePlayerDatabase,
    private val driver: SqlDriver,
    private val http: HttpClient,
    private val sources: SourceRepository,
    private val defaultUserAgent: String,
    private val searchIndex: SearchIndex,
) {
    private val q get() = db.schemaQueries

    /**
     * Imports the full XMLTV guide of a source. Only programmes for channels that exist in the
     * list and that fall in [now - archive, now + 7 days] are kept, so the DB stays small.
     */
    @Throws(Throwable::class)
    suspend fun refresh(source: Source) {
        val url = when (source.type) {
            SourceType.XTREAM -> source.epgUrl ?: sources.xtreamCredentials(source)?.let { XtreamUrls.xmltv(it) }
            SourceType.M3U -> source.epgUrl
        } ?: return

        val wanted = withContext(Dispatchers.IO) {
            q.allChannels(source.id).executeAsList().mapNotNull { it.epg_id?.takeIf { id -> id.isNotBlank() } }.toHashSet()
        }
        if (wanted.isEmpty()) return
        val maxArchiveDays = 7
        val from = nowMillis() - maxArchiveDays * DAY
        val to = nowMillis() + 7 * DAY

        val batch = ArrayList<XmltvProgramme>(BATCH)
        var first = true
        var touched = false
        // Raw cached statements: generated queries would notify every epg_program observer once
        // per batch (now/next lists re-querying dozens of times during an import). Listeners are
        // notified exactly once at the end instead, even if the import fails half way.
        fun flush() {
            if (batch.isEmpty() && !first) return
            db.transaction {
                if (first) {
                    driver.execute(ID_DELETE_SOURCE, SQL_DELETE_SOURCE, 1) { bindLong(0, source.id) }
                    first = false
                }
                batch.forEach { p ->
                    driver.execute(ID_INSERT, SQL_INSERT, 6) {
                        bindLong(0, source.id); bindString(1, p.channel); bindLong(2, p.start)
                        bindLong(3, p.stop); bindString(4, p.title); bindString(5, p.description)
                    }
                }
            }
            touched = true
            batch.clear()
        }

        var receiving = false
        try {
            http.prepareGet(url) {
                header(HttpHeaders.UserAgent, source.userAgent ?: defaultUserAgent)
            }.execute { response ->
                if (!response.status.isSuccess()) throw NetworkErrors.fromStatus(response.status.value, NetworkErrors.Target.GUIDE)
                receiving = true
                val body = response.bodyAsChannel()
                withContext(Dispatchers.IO) {
                    val parser = XmltvParser { p ->
                        if (p.channel in wanted && p.stop > from && p.start < to) {
                            batch += p
                            if (batch.size >= BATCH) flush()
                        }
                    }
                    val decoder = TextChunkDecoder { text -> parser.feed(text) }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = body.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n > 0) decoder.feed(buffer, n)
                    }
                    decoder.finish()
                    parser.finish()
                    if (!first || batch.isNotEmpty()) flush()
                }
            }
            withContext(Dispatchers.IO) {
                driver.execute(ID_DELETE_BEFORE, SQL_DELETE_BEFORE, 2) { bindLong(0, source.id); bindLong(1, from) }
                touched = true
                runCatching { searchIndex.rebuildEpg(source.id) }
                q.markEpgSynced(nowMillis(), source.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: XtreamException) {
            throw e
        } catch (e: Throwable) {
            // Precise message for network failures; storage/parsing errors keep their own.
            if (!receiving || NetworkErrors.causeOf(e) != null) throw NetworkErrors.fromThrowable(e, url, NetworkErrors.Target.GUIDE)
            throw e
        } finally {
            if (touched) driver.notifyListeners(EPG_TABLE)
        }
    }

    /** Guide for one channel. Falls back to the Xtream per-channel API if the XMLTV had nothing. */
    @Throws(Throwable::class)
    suspend fun programs(channel: Channel, from: Long, to: Long): List<EpgProgram> {
        val epgId = channel.epgId ?: return emptyList()
        val local = withContext(Dispatchers.IO) {
            q.programsForChannel(channel.sourceId, epgId, from, to).executeAsList().map { it.toModel() }
        }
        if (local.isNotEmpty()) return local
        val source = sources.byId(channel.sourceId) ?: return emptyList()
        val creds = sources.xtreamCredentials(source) ?: return emptyList()
        val remote = runCatchingCancellable {
            XtreamClient(http, creds, source.userAgent ?: defaultUserAgent).channelEpg(channel.id, epgId)
        }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) withContext(Dispatchers.IO) {
            db.transaction {
                q.deleteProgramsOfChannel(channel.sourceId, epgId)
                remote.forEach { q.insertProgram(channel.sourceId, epgId, it.startAt, it.endAt, it.title, it.description) }
            }
        }
        return remote.filter { it.endAt > from && it.startAt < to }
    }

    /**
     * Now/next for a list of channels, for channel lists and the mini-guide. Any number of
     * channels is fine: ids are queried in chunks of [MAX_IDS_PER_QUERY] (SQLite variable limit)
     * and the flow re-emits once per change of the programme table. On a query failure it emits
     * the channels without guide data instead of failing the collector.
     */
    fun nowNext(sourceId: Long, channels: List<Channel>, now: Long = nowMillis()): CFlow<List<ChannelWithEpg>> {
        val ids = channels.mapNotNull { it.epgId }.distinct()
        val noGuide = { channels.map { ChannelWithEpg(it, null, null) } }
        if (ids.isEmpty()) return flowOf(noGuide()).wrap()
        val to = now + 6 * HOUR
        val chunks = ids.chunked(MAX_IDS_PER_QUERY)
        // Every chunk query observes the same table, so the first one is enough as a trigger
        // (asFlow is conflated: a burst of writes yields one re-query).
        return q.programsForChannels(sourceId, chunks.first(), now, to).asFlow()
            .map {
                withContext(Dispatchers.IO) {
                    chunks.flatMap { chunk -> q.programsForChannels(sourceId, chunk, now, to).executeAsList() }
                }
            }
            .map { rows ->
                val byChannel = rows.map { it.toSlot() }.groupBy { it.channelEpgId }
                channels.map { ch ->
                    val list = ch.epgId?.let { byChannel[it] }.orEmpty()
                    val current = list.firstOrNull { it.isLive(now) }
                    val next = list.firstOrNull { it.startAt >= (current?.endAt ?: now) }
                    ChannelWithEpg(ch, current, next)
                }
            }.wrapOr(noGuide)
    }

    /** Programs of many channels in a time window (for the grid guide). */
    @Throws(Throwable::class)
    suspend fun guide(sourceId: Long, epgIds: List<String>, from: Long, to: Long): Map<String, List<EpgProgram>> =
        withContext(Dispatchers.IO) {
            if (epgIds.isEmpty()) return@withContext emptyMap()
            epgIds.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
                q.programsForChannels(sourceId, chunk, from, to).executeAsList()
            }.map { it.toSlot() }.groupBy { it.channelEpgId }
        }

    companion object {
        private const val BATCH = 2000
        /** Max ids bound in one IN (...) list: SQLite on Android 8 allows 999 variables per statement. */
        internal const val MAX_IDS_PER_QUERY = 500
        private const val EPG_TABLE = "epg_program"
        private const val SQL_INSERT =
            "INSERT INTO epg_program(source_id, channel_epg_id, start_at, end_at, title, description) VALUES (?, ?, ?, ?, ?, ?)"
        private const val SQL_DELETE_SOURCE = "DELETE FROM epg_program WHERE source_id = ?"
        private const val SQL_DELETE_BEFORE = "DELETE FROM epg_program WHERE source_id = ? AND end_at < ?"
        private val ID_INSERT = SQL_INSERT.hashCode() xor 0x0E9C0DE
        private val ID_DELETE_SOURCE = SQL_DELETE_SOURCE.hashCode() xor 0x0E9C0DE
        private val ID_DELETE_BEFORE = SQL_DELETE_BEFORE.hashCode() xor 0x0E9C0DE
        internal const val HOUR = 60 * 60 * 1000L
        internal const val DAY = 24 * HOUR
    }
}

private fun com.steplayer.shared.db.ProgramsForChannels.toSlot() =
    EpgProgram(channelEpgId = channel_epg_id, startAt = start_at, endAt = end_at, title = title, description = null)
