package com.steplayer.app.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.steplayer.app.app
import com.steplayer.shared.data.DownloadRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 14+ "user-initiated data transfer" job: the API Google designed for downloads the
 * user explicitly asked for. Unlike normal background work it isn't cut after 10 minutes,
 * isn't subject to standby quotas, keeps running with the screen off or the app closed, and
 * the system restarts it by itself when the network comes back.
 */
@RequiresApi(34)
class DownloadJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val request = DownloadRequestCodec.fromBundle(params.extras) ?: return false
        // The system reschedules with the same extras: the attempt count lives in DownloadAttempts.
        val attempts = DownloadAttempts.of(this)
        val attempt = maxOf(DownloadRequestCodec.attempt(params.extras), attempts.get(request.id))
        val notificationId = DownloadNotifications.idFor(request.id)
        // Mandatory for user-initiated jobs, right away.
        setNotification(
            params, notificationId,
            DownloadNotifications.build(this, request.id, request.title, 0, 0, null),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        running[params.jobId] = app.appScope.launch {
            val outcome = DownloadExecutor(this@DownloadJobService).execute(request, attempt) { n ->
                runCatching { setNotification(params, notificationId, n, JOB_END_NOTIFICATION_POLICY_REMOVE) }
            }
            running.remove(params.jobId)
            when (outcome) {
                DownloadOutcome.RETRY -> {
                    // Reschedule with a backoff (system handles the delay); the next run sees attempt + 1.
                    attempts.increment(request.id)
                    jobFinished(params, true)
                }
                DownloadOutcome.STOPPED -> Unit // onStopJob already answered the system
                else -> {
                    attempts.clear(request.id)
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        // Reschedule unless the app (pause/delete) or the user (Task Manager) stopped it.
        return when (params.stopReason) {
            JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.STOP_REASON_USER -> {
                if (params.stopReason == JobParameters.STOP_REASON_USER) {
                    DownloadRequestCodec.fromBundle(params.extras)?.let { r ->
                        app.appScope.launch { app.core.downloads.pause(r.id) }
                    }
                }
                false
            }
            else -> {
                DownloadRequestCodec.fromBundle(params.extras)?.let { app.core.downloads.onQueued(it.id) }
                true
            }
        }
    }

    companion object {
        fun jobId(downloadId: String) = 0x5D000000 or (downloadId.hashCode() and 0x00FFFFFF)

        /**
         * Must be called while the app is visible (the user tapped "Scarica"/"Riprendi").
         * @return false if the system refused (e.g. app in background): use the fallback.
         */
        fun schedule(context: Context, request: DownloadRequest): Boolean {
            if (Build.VERSION.SDK_INT < 34) return false
            return runCatching {
                val info = JobInfo.Builder(jobId(request.id), ComponentName(context, DownloadJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                    .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setExtras(DownloadRequestCodec.toBundle(request))
                    .build()
                context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }

        fun cancel(context: Context, downloadId: String) {
            if (Build.VERSION.SDK_INT < 34) return
            runCatching { context.getSystemService(JobScheduler::class.java).cancel(jobId(downloadId)) }
        }
    }
}
