package com.steplayer.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.steplayer.app.MainActivity
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Schedules the "subscription expires in 3 days / 1 day" notifications with WorkManager.
 * Re-runs whenever lists change (sync, add, edit, delete) or the user toggles the setting.
 */
object ExpiryScheduler {
    private const val TAG = "expiry"
    const val CHANNEL = "expiry_reminders"

    fun observe(app: StePlayerApp) {
        app.appScope.launch {
            combine(app.core.sources.sources, app.core.reminders.enabled) { s, e -> s to e }
                .debounce(1_000)
                .collect { runCatching { reschedule(app) } }
        }
    }

    suspend fun reschedule(app: StePlayerApp) {
        if (app.isTv) return
        val wm = WorkManager.getInstance(app)
        wm.cancelAllWorkByTag(TAG)
        val now = System.currentTimeMillis()
        val offsetSec = TimeZone.getDefault().getOffset(now) / 1000L
        app.core.reminders.pendingReminders(offsetSec).forEach { r ->
            val delay = (r.fireAtMs - now).coerceAtLeast(0)
            val work = OneTimeWorkRequestBuilder<ExpiryReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("id" to r.id, "title" to r.title, "body" to r.body))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(r.id, ExistingWorkPolicy.REPLACE, work)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.expiry_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
class ExpiryReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: return Result.success()
        val body = inputData.getString("body").orEmpty()
        val id = inputData.getString("id").orEmpty()
        ExpiryScheduler.ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, id.hashCode(),
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, ExpiryScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(id.hashCode(), notification) }
        return Result.success()
    }
}
