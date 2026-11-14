package com.steplayer.app.download

import android.os.Build
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.steplayer.app.app
import com.steplayer.shared.data.DownloadEngine
import com.steplayer.shared.data.DownloadRequest
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Picks the most reliable scheduler for each download:
 * - Android 14+ with the app on screen → user-initiated data transfer job (best background behaviour)
 * - otherwise → WorkManager foreground worker
 */
class AndroidDownloadEngine(private val context: Context) : DownloadEngine {
    private val wm get() = WorkManager.getInstance(context)

    private val appVisible: Boolean
        get() = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)

    override fun start(request: DownloadRequest) {
        DownloadAttempts.of(context).clear(request.id) // user action: fresh retry budget
        if (Build.VERSION.SDK_INT >= 34 && appVisible && DownloadJobService.schedule(context, request)) {
            wm.cancelUniqueWork(request.id) // never two schedulers for the same file
            return
        }
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadRequestCodec.toData(request))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(request.id, ExistingWorkPolicy.REPLACE, work)
    }

    /** Stops the transfer; partial data and resume state are kept. */
    override fun pause(id: String) {
        ActiveDownloads.shared.stop(id) // stops the transfer now; the schedulers are told below
        if (Build.VERSION.SDK_INT >= 34) DownloadJobService.cancel(context, id)
        wm.cancelUniqueWork(id)
        DownloadSpeeds.clear(id)
    }

    override fun cancel(id: String) {
        pause(id)
        DownloadAttempts.of(context).clear(id)
        val prefix = id.replace(':', '_') + "."
        File(context.app.platform.downloadsDirectory).listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    companion object {
        const val TAG = "steplayer-download"
    }
}
