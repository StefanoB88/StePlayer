package com.steplayer.app.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.steplayer.app.app
import com.steplayer.shared.data.DownloadRequest
import com.steplayer.shared.playback.ConnectionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the scheduler (JobScheduler or WorkManager) should do after a run. */
enum class DownloadOutcome { SUCCESS, RETRY, FAILURE, STOPPED }

/**
 * Runs one download end-to-end, independent of who scheduled it:
 * connection leases (respecting the account's limit), Wi-Fi/CPU locks, the segmented transfer,
 * progress + speed reporting and error mapping. Never logs URLs (they embed credentials).
 */
internal class DownloadExecutor(private val context: Context) {
    private val app = context.app

    suspend fun execute(
        request: DownloadRequest,
        attempt: Int,
        onNotify: (android.app.Notification) -> Unit,
    ): DownloadOutcome {
        val downloads = app.core.downloads
        val guard = app.core.playback.connections
        val sourceId = request.id.substringBefore(':').toLongOrNull() ?: return DownloadOutcome.FAILURE
        val file = File(app.platform.downloadsDirectory, request.fileName)
        val leases = ArrayList<ConnectionLease>()
        val wifi = wifiLock()
        val cpu = cpuLock()
        val job = currentCoroutineContext().job
        val active = ActiveDownloads.shared
        active.register(request.id, job)
        /** Status writes only while this transfer is still wanted (see [ActiveDownloads]). */
        fun report(block: () -> Unit) { active.whileActive(request.id, job, block) }
        try {
            // 1) One connection is mandatory: wait (QUEUED) until the account has a free slot.
            var first = guard.acquireDownload(sourceId, request.title)
            if (first == null) {
                report {
                    downloads.onQueued(request.id)
                    onNotify(DownloadNotifications.build(context, request.id, request.title, 0, 0, null, waiting = WAITING))
                }
                while (first == null) {
                    guard.active.drop(1).first()
                    first = guard.acquireDownload(sourceId, request.title)
                }
            }
            leases += first
            // 2) Extra parallel connections only if the account has spare ones (never waits for them).
            while (leases.size < DownloadTransfer.MAX_SEGMENTS) {
                leases += guard.acquireDownload(sourceId, request.title) ?: break
            }
            // Keep the extra slots only as long as the transfer needs them: it decides how many to use.
            var connections = leases.size

            wifi?.acquire()
            cpu?.acquire(6 * 60 * 60 * 1000L)

            val meter = SpeedMeter()
            report { downloads.onProgress(request.id, 0, 0) }
            val transfer = DownloadTransfer(client, request.url, request.headers, file)
            val onConnections: (Int) -> Unit = { used ->
                // Give back the slots it won't use (small or resumed files), so other downloads can start.
                while (leases.size > used.coerceAtLeast(1)) leases.removeAt(leases.lastIndex).release()
                connections = used
            }
            transfer.run(connections, onConnections) { bytes, total ->
                val bps = meter.sample(bytes, System.currentTimeMillis())
                val eta = if (bps > 0 && total > bytes) (total - bytes) / bps else null
                val speed = SpeedInfo(bps, eta, connections)
                report {
                    DownloadSpeeds.set(request.id, speed)
                    downloads.onProgress(request.id, bytes, total)
                    onNotify(DownloadNotifications.build(context, request.id, request.title, bytes, total, speed))
                }
            }
            currentCoroutineContext().ensureActive()
            report { downloads.onCompleted(request.id, request.fileName) }
            return DownloadOutcome.SUCCESS
        } catch (e: CancellationException) {
            // Paused/deleted by the user or stopped by the system: the repository/scheduler handle the status.
            return DownloadOutcome.STOPPED
        } catch (e: NotEnoughSpaceException) {
            if (!job.isActive) return DownloadOutcome.STOPPED
            report { downloads.onFailed(request.id, "Spazio insufficiente sul dispositivo") }
            return DownloadOutcome.FAILURE
        } catch (e: HttpStatusException) {
            if (!job.isActive) return DownloadOutcome.STOPPED
            val retryable = e.code in 500..599 || e.code in setOf(403, 406, 429, 458, 509)
            return if (retryable && attempt < MAX_ATTEMPTS) {
                report { downloads.onQueued(request.id) }
                DownloadOutcome.RETRY
            } else {
                report { downloads.onFailed(request.id, httpMessage(e.code)) }
                DownloadOutcome.FAILURE
            }
        } catch (e: IOException) {
            // A paused/deleted transfer often ends with an I/O error from its closed socket: not a failure.
            if (!job.isActive) return DownloadOutcome.STOPPED
            return if (attempt < MAX_ATTEMPTS) {
                report { downloads.onQueued(request.id) } // network lost: resumes automatically
                DownloadOutcome.RETRY
            } else {
                report { downloads.onFailed(request.id, "Connessione interrotta") }
                DownloadOutcome.FAILURE
            }
        } finally {
            active.unregister(request.id, job)
            leases.forEach { it.release() }
            DownloadSpeeds.clear(request.id)
            if (wifi?.isHeld == true) wifi.release()
            if (cpu?.isHeld == true) cpu.release()
        }
    }

    private fun httpMessage(code: Int) = when (code) {
        401 -> "Accesso negato dal provider"
        404, 410 -> "Il contenuto non è più disponibile"
        403, 458, 509 -> "Il provider rifiuta la connessione (limite di connessioni?)"
        else -> "Il server ha risposto con errore $code"
    }

    /** Keeps Wi-Fi at full speed with the screen off (power-save mode can slash throughput). */
    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun wifiLock(): WifiManager.WifiLock? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
        wm.createWifiLock(mode, "StePlayer:download").apply { setReferenceCounted(false) }
    }.getOrNull()

    private fun cpuLock(): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StePlayer:download").apply { setReferenceCounted(false) }
    }.getOrNull()

    companion object {
        const val MAX_ATTEMPTS = 8
        private const val WAITING = "In attesa di una connessione libera…"

        /** Download-only client: long read timeout, no idle sockets left open towards the provider. */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
