package com.steplayer.app.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Fallback scheduler (Android < 14, or when a user-initiated job can't be scheduled because the
 * app is in the background). Runs as a foreground service with a progress notification.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val request = DownloadRequestCodec.fromData(inputData)
    private val notificationId = DownloadNotifications.idFor(request?.id.orEmpty())

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(DownloadNotifications.build(applicationContext, request?.id.orEmpty(), request?.title.orEmpty(), 0, 0, null))

    override suspend fun doWork(): Result {
        val r = request ?: return Result.failure()
        // May be refused when started from the background on Android 12+; the transfer still
        // runs and resumes on the next attempt if the system stops it.
        runCatching { setForeground(getForegroundInfo()) }
        var lastNotify = 0L
        val outcome = DownloadExecutor(applicationContext).execute(r, runAttemptCount) { n ->
            val now = System.currentTimeMillis()
            if (now - lastNotify >= 1_000) {
                lastNotify = now
                runCatching { setForegroundAsync(foreground(n)) }
            }
        }
        return when (outcome) {
            DownloadOutcome.SUCCESS -> Result.success()
            DownloadOutcome.RETRY -> Result.retry()
            DownloadOutcome.FAILURE -> Result.failure()
            DownloadOutcome.STOPPED -> Result.retry() // system stop: WorkManager reschedules; user stop cancels the work
        }
    }

    private fun foreground(n: android.app.Notification) = if (Build.VERSION.SDK_INT >= 29) {
        ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(notificationId, n)
    }
}
