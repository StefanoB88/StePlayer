package com.steplayer.app.download

import kotlinx.coroutines.Job

/**
 * In-process registry of running transfers, so that pause/delete stop a transfer immediately and
 * for good, instead of waiting for JobScheduler/WorkManager to deliver the stop asynchronously.
 *
 * Without it a progress callback that was already in flight could write "RUNNING" (or "QUEUED"
 * after the cancelled socket failed) over the "PAUSED" the user just set, leaving a paused download
 * shown as running, with no transfer behind it, and auto-resumed at the next app start.
 *
 * [whileActive] and [stop] share one lock: once [stop] returns, no status write of that transfer
 * can happen any more.
 */
internal class ActiveDownloads {
    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    /** Registers [job] as the transfer of [id]; an older transfer of the same file is cancelled (never two at once). */
    fun register(id: String, job: Job) = synchronized(lock) {
        jobs.put(id, job)?.takeIf { it !== job }?.cancel()
    }

    fun unregister(id: String, job: Job) = synchronized(lock) {
        if (jobs[id] === job) jobs.remove(id)
    }

    /** Cancels the running transfer of [id], if any. */
    fun stop(id: String) = synchronized(lock) {
        jobs.remove(id)?.cancel()
        Unit
    }

    /** Runs [block] only while [job] is still the active transfer (not paused/deleted meanwhile). */
    fun <T> whileActive(id: String, job: Job, block: () -> T): T? = synchronized(lock) {
        if (job.isActive && jobs[id] === job) block() else null
    }

    companion object {
        val shared = ActiveDownloads()
    }
}

/**
 * Attempt counter for user-initiated jobs (JobScheduler reschedules with the same extras, so the
 * attempt number can't travel in the job itself). Kept in memory and in a tiny prefs file.
 */
internal class DownloadAttempts(private val prefs: android.content.SharedPreferences) {
    fun get(id: String): Int = prefs.getInt(id, 0)
    fun increment(id: String) = prefs.edit().putInt(id, get(id) + 1).apply()
    fun clear(id: String) = prefs.edit().remove(id).apply()

    companion object {
        fun of(context: android.content.Context) =
            DownloadAttempts(context.applicationContext.getSharedPreferences("download_attempts", android.content.Context.MODE_PRIVATE))
    }
}
