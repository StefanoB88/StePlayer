package com.steplayer.shared.diagnostics

import com.steplayer.shared.util.runCatchingCancellable
import com.steplayer.shared.data.CatalogRepository
import com.steplayer.shared.data.Categories
import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.data.StreamResolver
import com.steplayer.shared.model.Channel
import com.steplayer.shared.playback.AcquireResult
import com.steplayer.shared.playback.ConnectionGuard
import com.steplayer.shared.playback.PlaybackErrors
import com.steplayer.shared.settings.BufferMode
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.wrap
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.TimeSource

enum class ConnectionVerdict { GOOD, PROVIDER_SLOW, PROVIDER_UNSTABLE, HOME_NETWORK_SLOW, OFFLINE, PROVIDER_ERROR }

data class ConnectionTestResult(
    val verdict: ConnectionVerdict,
    val title: String,
    val message: String,
    /** Time to first byte of the stream, ms. */
    val providerFirstByteMs: Long?,
    /** Sustained stream throughput, megabit/s. */
    val providerMbps: Double?,
    /** Worst 1-second window / average (1.0 = perfectly steady). */
    val providerStability: Double?,
    /** Generic Internet throughput (reference server), megabit/s. Null if not measured. */
    val internetMbps: Double?,
    val recommendedBuffer: BufferMode,
    val testedChannelName: String?,
)

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data class Running(val step: String, val progress: Float) : ConnectionTestState
    data class Done(val result: ConnectionTestResult) : ConnectionTestState
}

/**
 * "Test connessione": measures the provider's real stream speed and compares it with a neutral
 * reference download, to tell the user whether stutters come from their home network or from
 * the provider. Runs only when the user asks; holds a connection lease while testing
 * (the provider counts the test as one connection) and always closes it.
 */
class ConnectionTest internal constructor(
    private val http: HttpClient,
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val streams: StreamResolver,
    private val guard: ConnectionGuard,
) {
    private val _state = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val state: CFlow<ConnectionTestState> = _state.asStateFlow().wrap()

    private class Sample(val firstByteMs: Long?, val mbps: Double?, val stability: Double?, val httpStatus: Int?, val failed: Throwable?)

    /**
     * @param channel channel to test (default: the first channel of the list).
     * @param referenceUrl neutral large file used to measure the home connection; null to skip.
     *        Only an anonymous GET is made: no personal data is sent.
     */
    @Throws(Throwable::class)
    suspend fun run(
        sourceId: Long,
        channel: Channel? = null,
        referenceUrl: String? = DEFAULT_REFERENCE,
    ): ConnectionTestResult {
        try {
            val target = channel ?: catalog.channels(sourceId, Categories.ALL).first().firstOrNull()

            var internet: Double? = null
            if (referenceUrl != null) {
                _state.value = ConnectionTestState.Running("Velocità della tua connessione…", 0.1f)
                internet = measure(referenceUrl, emptyMap(), seconds = 5).mbps
            }

            if (target == null) {
                return finish(verdictWithoutProvider(internet, null))
            }

            _state.value = ConnectionTestState.Running("Velocità del provider…", 0.5f)
            val lease = when (val r = guard.acquirePlayback(sourceId, "Test connessione") {}) {
                is AcquireResult.Granted -> r.lease
                is AcquireResult.Denied -> return finish(
                    ConnectionTestResult(ConnectionVerdict.PROVIDER_ERROR, "Connessione occupata", r.message, null, null, null,
                        internet, BufferMode.LARGE, target.name),
                )
            }
            val provider = try {
                val stream = streams.live(target)
                val url = if (stream.container == "m3u8" || stream.url.substringBefore('?').endsWith(".m3u8")) {
                    resolveHlsSegment(stream.url, stream.headers) ?: stream.url
                } else stream.url
                measure(url, stream.headers, seconds = 7)
            } finally {
                lease.release() // the provider connection is closed by now: measure() returned
            }
            return finish(evaluate(provider, internet, target.name, sourceId))
        } catch (e: CancellationException) {
            _state.value = ConnectionTestState.Idle
            throw e
        }
    }

    private fun finish(result: ConnectionTestResult): ConnectionTestResult {
        _state.value = ConnectionTestState.Done(result)
        return result
    }

    fun reset() { _state.value = ConnectionTestState.Idle }

    // ------------------------------------------------------------------ measuring

    /** Downloads for at most [seconds] (or 40 MB) and closes the connection. */
    private suspend fun measure(url: String, headers: Map<String, String>, seconds: Int): Sample {
        val clock = TimeSource.Monotonic
        val start = clock.markNow()
        return try {
            http.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { response ->
                if (!response.status.isSuccess()) return@execute Sample(null, null, null, response.status.value, null)
                val body = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var firstByte: Long? = null
                var total = 0L
                val perSecond = LongArray(seconds + 1)
                var dataStart = 0L
                while (true) {
                    val n = body.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    val elapsed = start.elapsedNow().inWholeMilliseconds
                    if (firstByte == null && n > 0) { firstByte = elapsed; dataStart = elapsed }
                    total += n
                    val sec = ((elapsed - dataStart) / 1000).toInt()
                    if (sec in perSecond.indices) perSecond[sec] += n.toLong()
                    if (elapsed - dataStart >= seconds * 1000L || total >= 40L * 1024 * 1024) break
                }
                // Returning from execute{} cancels and closes the response: no connection left open.
                val durationMs = (start.elapsedNow().inWholeMilliseconds - dataStart).coerceAtLeast(1)
                val mbps = total * 8.0 / durationMs / 1000.0
                val full = perSecond.take((durationMs / 1000).toInt().coerceIn(1, seconds)).filter { it > 0 }
                val stability = if (full.size >= 3) full.min().toDouble() / (full.sum().toDouble() / full.size) else null
                Sample(firstByte, if (total > 0) mbps else null, stability, response.status.value, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Sample(null, null, null, null, e)
        }
    }

    /** For HLS: pick the first variant, then the newest media segment. */
    private suspend fun resolveHlsSegment(url: String, headers: Map<String, String>, depth: Int = 0): String? {
        if (depth > 2) return null
        val text = runCatchingCancellable {
            http.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { it.bodyAsText() }
        }.getOrNull() ?: return null
        val uris = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (uris.isEmpty()) return null
        val isMaster = "#EXT-X-STREAM-INF" in text
        val next = URLBuilder().takeFrom(url).apply { takeFrom(if (isMaster) uris.first() else uris.last()) }.buildString()
        return if (isMaster) resolveHlsSegment(next, headers, depth + 1) else next
    }

    // ------------------------------------------------------------------ verdict

    private suspend fun evaluate(p: Sample, internet: Double?, channelName: String, sourceId: Long): ConnectionTestResult {
        if (p.mbps == null) {
            if (internet == null && p.failed != null) return verdictWithoutProvider(null, channelName)
            val err = p.httpStatus?.let { PlaybackErrors.fromHttpStatus(it, sources.byId(sourceId)?.maxConnections) }
                ?: PlaybackErrors.timeout()
            return ConnectionTestResult(ConnectionVerdict.PROVIDER_ERROR, err.title,
                "Il canale di prova «$channelName» non ha inviato dati. ${err.message}",
                p.firstByteMs, null, null, internet, BufferMode.LARGE, channelName)
        }
        val mbps = p.mbps
        val unstable = (p.stability ?: 1.0) < 0.35
        val homeSlow = internet != null && internet < HOME_MIN_MBPS
        val slowStart = (p.firstByteMs ?: 0) > 2500
        return when {
            homeSlow -> ConnectionTestResult(ConnectionVerdict.HOME_NETWORK_SLOW, "La tua rete è lenta",
                "La connessione Internet arriva a ${fmt(internet!!)} Mbps: per un video HD stabile ne servono almeno $HD_MBPS. " +
                    "Avvicina il dispositivo al router, usa la rete Wi-Fi a 5 GHz o il cavo Ethernet.",
                p.firstByteMs, mbps, p.stability, internet, BufferMode.LARGE, channelName)
            mbps < PROVIDER_MIN_MBPS -> ConnectionTestResult(ConnectionVerdict.PROVIDER_SLOW, "Il provider è lento",
                "Il server del provider invia ${fmt(mbps)} Mbps" +
                    (internet?.let { " mentre la tua rete arriva a ${fmt(it)} Mbps" } ?: "") +
                    ": i blocchi dipendono dal provider. Ho impostato il buffer «Grande» per ridurli; se continuano, contatta il provider.",
                p.firstByteMs, mbps, p.stability, internet, BufferMode.LARGE, channelName)
            unstable -> ConnectionTestResult(ConnectionVerdict.PROVIDER_UNSTABLE, "Velocità irregolare",
                "La velocità media è buona (${fmt(mbps)} Mbps) ma con cali improvvisi. Il buffer «Grande» aiuta a evitare i blocchi.",
                p.firstByteMs, mbps, p.stability, internet, BufferMode.LARGE, channelName)
            else -> ConnectionTestResult(ConnectionVerdict.GOOD, "Tutto a posto",
                "Il provider invia ${fmt(mbps)} Mbps" + (internet?.let { " e la tua rete ne regge ${fmt(it)}" } ?: "") + ". " +
                    (if (slowStart) "L'avvio dei canali può essere un po' lento perché il server risponde in ${p.firstByteMs} ms." else "La connessione è adatta alla visione in HD."),
                p.firstByteMs, mbps, p.stability, internet, BufferMode.LARGE, channelName)
        }
    }

    private fun verdictWithoutProvider(internet: Double?, channelName: String?) = if (internet == null) {
        ConnectionTestResult(ConnectionVerdict.OFFLINE, "Nessuna connessione",
            "Il dispositivo non riesce a raggiungere Internet. Controlla Wi-Fi o cavo di rete.",
            null, null, null, null, BufferMode.LARGE, channelName)
    } else {
        ConnectionTestResult(ConnectionVerdict.PROVIDER_ERROR, "Nessun canale da provare",
            "La tua rete funziona (${fmt(internet)} Mbps). Sincronizza la lista e riprova il test.",
            null, null, null, internet, BufferMode.LARGE, channelName)
    }

    private fun fmt(v: Double): String {
        val r = kotlin.math.round(v * 10) / 10
        return if (r >= 10) r.toInt().toString() else r.toString().replace('.', ',')
    }

    companion object {
        /** Anonymous 25 MB download from Cloudflare's public speed-test endpoint. */
        const val DEFAULT_REFERENCE = "https://speed.cloudflare.com/__down?bytes=25000000"
        private const val HD_MBPS = 8
        private const val HOME_MIN_MBPS = 8.0
        private const val PROVIDER_MIN_MBPS = 5.0
    }
}
