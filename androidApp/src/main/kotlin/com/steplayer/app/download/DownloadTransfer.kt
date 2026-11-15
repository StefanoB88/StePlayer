package com.steplayer.app.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class HttpStatusException(val code: Int) : IOException("HTTP $code")
class NotEnoughSpaceException(val neededBytes: Long) : IOException("Not enough space")

/**
 * Downloads one file as fast as the provider allows, resumable at any point.
 *
 * - **Segmented**: if the server supports HTTP ranges and [maxConnections] > 1, the file is split
 *   in up to that many parts downloaded in parallel (many streaming servers cap speed per connection).
 *   Never more connections than the caller was granted by the ConnectionGuard.
 * - **Resumable**: progress of every segment is persisted in `<file>.state` once per second, so a
 *   pause, network loss, process death or reboot continues where it stopped.
 * - **Efficient I/O**: 512 KB reads, positional writes, no transparent gzip (`identity`), which
 *   would otherwise break Content-Length and Range on misconfigured servers.
 *
 * Pure JVM (OkHttp + java.nio): unit-tested with MockWebServer.
 */
class DownloadTransfer(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val file: File,
    private val minSegmentBytes: Long = 16L * 1024 * 1024,
) {
    data class Segment(val start: Long, val end: Long, @Volatile var done: Long) {
        val next: Long get() = start + done
        val complete: Boolean get() = next > end
    }

    private val stateFile = File(file.path + ".state")

    /**
     * @param maxConnections parallel connections allowed (≥ 1).
     * @param onConnections called once, before the transfer starts, with the number of parallel
     *   connections it will really use (≤ [maxConnections]: small files and resumed single-stream
     *   files use fewer), so the caller can give back the slots it doesn't need.
     * @param onProgress called about once per second with (downloadedBytes, totalBytes or 0 if unknown).
     * @return final file size.
     */
    suspend fun run(
        maxConnections: Int,
        onConnections: (Int) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val saved = readState()
        if (saved != null && file.exists()) {
            val (total, segments) = saved
            return@withContext runSegments(total, segments, maxConnections, onConnections, onProgress, first = null)
        }
        stateFile.delete()

        // Probe: open-ended range from what we already have (legacy single-stream partial files too).
        val existing = if (file.exists()) file.length() else 0L
        val probe = open(existing, null)
        val code = probe.code
        if (code == 416 && existing > 0) {
            probe.close()
            return@withContext existing // already complete
        }
        if (code !in 200..299) {
            probe.close()
            throw HttpStatusException(code)
        }
        val total = totalFrom(probe, existing)
        val supportsRanges = code == 206

        if (!supportsRanges || total <= 0) {
            // Server ignores ranges or doesn't tell the size: one sequential stream from the start.
            onConnections(1)
            return@withContext sequential(probe, onProgress)
        }
        ensureSpace(total - existing)
        if (existing > 0) {
            // Continue a single-stream partial file as one segment.
            val segments = listOf(Segment(0, total - 1, existing))
            preallocate(total)
            return@withContext runSegments(total, segments, 1, onConnections, onProgress, first = probe)
        }
        val parts = maxConnections.coerceIn(1, MAX_SEGMENTS)
            .coerceAtMost((total / minSegmentBytes).toInt().coerceAtLeast(1))
        val size = total / parts
        val segments = (0 until parts).map { i ->
            val start = i * size
            val end = if (i == parts - 1) total - 1 else start + size - 1
            Segment(start, end, 0)
        }
        preallocate(total)
        writeState(total, segments)
        runSegments(total, segments, maxConnections, onConnections, onProgress, first = probe)
    }

    // ------------------------------------------------------------------ segmented

    private suspend fun runSegments(
        total: Long,
        segments: List<Segment>,
        maxConnections: Int,
        onConnections: (Int) -> Unit,
        onProgress: (Long, Long) -> Unit,
        first: Response?,
    ): Long = coroutineScope {
        val downloaded = AtomicLong(segments.sumOf { it.done })
        val connections = minOf(maxConnections, segments.count { !it.complete }).coerceAtLeast(1)
        onConnections(connections)
        val permits = Semaphore(connections)
        RandomAccessFile(file, "rw").use { raf ->
            val channel = raf.channel
            val reporter = launch {
                while (isActive) {
                    delay(1_000)
                    onProgress(downloaded.get(), total)
                    writeState(total, segments)
                }
            }
            var firstResponse = first
            try {
                segments.filter { !it.complete }.mapIndexed { index, segment ->
                    // The probe response starts at segment 0: reuse it instead of reconnecting.
                    val reuse = if (index == 0 && firstResponse != null && segment.next == segments.first().next) {
                        firstResponse.also { firstResponse = null }
                    } else null
                    async { permits.withPermit { downloadSegment(segment, channel, downloaded, reuse) } }
                }.awaitAll()
            } finally {
                firstResponse?.close()
                reporter.cancel()
                writeState(total, segments)
            }
            channel.force(false)
        }
        if (segments.any { !it.complete }) throw IOException("Incomplete download")
        stateFile.delete()
        onProgress(total, total)
        total
    }

    private suspend fun downloadSegment(segment: Segment, channel: FileChannel, downloaded: AtomicLong, reuse: Response?) {
        var attempt = 0
        var response = reuse
        while (!segment.complete) {
            coroutineContext.ensureActive()
            try {
                val r = response ?: open(segment.next, segment.end)
                response = null
                r.use {
                    if (r.code != 206) throw if (r.code in 200..299) IOException("Range not honoured") else HttpStatusException(r.code)
                    val source = r.body!!.source()
                    val buffer = ByteArray(BUFFER)
                    while (!segment.complete) {
                        coroutineContext.ensureActive()
                        val want = minOf(BUFFER.toLong(), segment.end - segment.next + 1).toInt()
                        val n = fill(source, buffer, want)
                        if (n < 0) throw IOException("Connection closed early")
                        val bb = ByteBuffer.wrap(buffer, 0, n)
                        var pos = segment.next
                        while (bb.hasRemaining()) pos += channel.write(bb, pos)
                        segment.done += n
                        downloaded.addAndGet(n.toLong())
                        attempt = 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                // Transient: reconnect this segment from where it stopped.
                if (++attempt > SEGMENT_RETRIES) throw e
                delay(1_000L * attempt)
            }
        }
    }

    // ------------------------------------------------------------------ sequential fallback

    private suspend fun sequential(response: Response, onProgress: (Long, Long) -> Unit): Long {
        val total = response.body?.contentLength()?.takeIf { it > 0 } ?: 0L
        if (total > 0) ensureSpace(total)
        var downloaded = 0L
        var last = 0L
        response.use { r ->
            val source = r.body!!.source()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                val buffer = ByteArray(BUFFER)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = fill(source, buffer, buffer.size)
                    if (n < 0) break
                    raf.write(buffer, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 1_000) { last = now; onProgress(downloaded, total) }
                }
                raf.fd.sync()
            }
        }
        if (total > 0 && downloaded < total) throw IOException("Connection closed early")
        onProgress(downloaded, downloaded)
        return downloaded
    }

    // ------------------------------------------------------------------ helpers

    /** Reads until [max] bytes or ~256 KB are available (fewer, larger disk writes). -1 at EOF. */
    private fun fill(source: okio.BufferedSource, buf: ByteArray, max: Int): Int {
        var off = 0
        while (off < max) {
            val n = source.read(buf, off, max - off)
            if (n < 0) return if (off == 0) -1 else off
            off += n
            if (off >= MIN_WRITE) break
        }
        return off
    }

    private fun open(from: Long, to: Long?): Response {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Accept-Encoding", "identity")
            header("Range", "bytes=$from-${to ?: ""}")
        }.build()
        return client.newCall(request).execute()
    }

    /** Total size from Content-Range ("bytes 0-99/1234") or Content-Length. */
    private fun totalFrom(r: Response, offset: Long): Long {
        r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
        val len = r.body?.contentLength() ?: -1
        return if (len > 0) (if (r.code == 206) offset + len else len) else 0L
    }

    private fun ensureSpace(needed: Long) {
        val free = file.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (free < needed + SPACE_MARGIN) throw NotEnoughSpaceException(needed)
    }

    private fun preallocate(total: Long) {
        RandomAccessFile(file, "rw").use { if (it.length() != total) it.setLength(total) }
    }

    @Synchronized
    private fun writeState(total: Long, segments: List<Segment>) {
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(buildString {
            append("v1 ").append(total).append('\n')
            segments.forEach { append(it.start).append(' ').append(it.end).append(' ').append(it.done).append('\n') }
        })
        tmp.renameTo(stateFile)
    }

    private fun readState(): Pair<Long, List<Segment>>? = runCatching {
        val lines = stateFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: return null
        val total = lines.first().removePrefix("v1 ").trim().toLong()
        val segments = lines.drop(1).map { l ->
            val (s, e, d) = l.trim().split(' ').map { it.toLong() }
            Segment(s, e, d.coerceIn(0, e - s + 1))
        }
        if (segments.isEmpty() || segments.last().end != total - 1) null else total to segments
    }.getOrNull()

    companion object {
        const val MAX_SEGMENTS = 4
        private const val BUFFER = 512 * 1024
        private const val MIN_WRITE = 256 * 1024
        private const val SEGMENT_RETRIES = 4
        private const val SPACE_MARGIN = 200L * 1024 * 1024

        /** Removes the file and its resume state. */
        fun deleteFiles(file: File) {
            file.delete()
            File(file.path + ".state").delete()
            File(file.path + ".state.tmp").delete()
        }
    }
}
