package com.steplayer.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.steplayer.app.MainActivity
import com.steplayer.app.R
import com.steplayer.app.util.Format

internal object DownloadNotifications {
    const val CHANNEL = "downloads"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun idFor(downloadId: String) = 0x5D000000 or (downloadId.hashCode() and 0x00FFFFFF)

    /** "45% · 12,3 MB/s · 4 min rimanenti" or "In attesa di connessione…". */
    fun build(context: Context, downloadId: String, title: String, bytes: Long, total: Long, speed: SpeedInfo?, waiting: String? = null): android.app.Notification {
        ensureChannel(context)
        val percent = if (total > 0) ((bytes * 100) / total).toInt() else 0
        val text = waiting ?: buildList {
            if (total > 0) add("$percent%")
            speed?.takeIf { it.bytesPerSecond > 0 }?.let { s ->
                add(Format.speed(s.bytesPerSecond))
                s.etaSeconds?.let { add(Format.eta(it)) }
            }
        }.joinToString(" · ").ifEmpty { context.getString(R.string.status_running) }

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getBroadcast(
            context, idFor(downloadId),
            Intent(context, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.ACTION_PAUSE)
                .putExtra(DownloadActionReceiver.EXTRA_ID, downloadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.download_notification_title, title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent, total <= 0 && waiting == null)
            .addAction(0, context.getString(R.string.pause), pause)
            .build()
    }
}
