package com.steplayer.app.util

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    private val time = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.ITALY) }
    private val dayTime = ThreadLocal.withInitial { SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALY) }

    fun clock(epochMs: Long): String = time.get()!!.format(Date(epochMs))
    fun dayTime(epochMs: Long): String = dayTime.get()!!.format(Date(epochMs))
    fun date(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.ITALY).format(Date(epochMs))

    /** 01:23:45 or 23:45 */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** "1,2 GB", "300 MB", "850 KB", "12 B". */
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.ITALY, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.ITALY, "%.0f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.ITALY, "%.0f KB", b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    /** Transfer speed: "12,4 MB/s", "850 KB/s". */
    fun speed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1L shl 20 -> String.format(Locale.ITALY, "%.1f MB/s", bytesPerSecond / (1L shl 20).toDouble())
        else -> String.format(Locale.ITALY, "%.0f KB/s", bytesPerSecond / 1024.0)
    }

    /** Remaining time: "40 s rimanenti", "12 min rimanenti", "1 h 05 min rimanenti". */
    fun eta(seconds: Long): String = when {
        seconds < 60 -> "$seconds s rimanenti"
        seconds < 3600 -> "${(seconds + 59) / 60} min rimanenti"
        else -> String.format(Locale.ITALY, "%d h %02d min rimanenti", seconds / 3600, (seconds % 3600) / 60)
    }

    /** Short remaining time for compact status lines: "40 s", "3 min", "1 h 05 min". */
    fun remaining(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(0)} s"
        seconds < 3600 -> "${(seconds + 59) / 60} min"
        else -> String.format(Locale.ITALY, "%d h %02d min", seconds / 3600, (seconds % 3600) / 60)
    }

    /**
     * Running time in minutes: "45 min"; under a minute "< 1 min" (never "0 min");
     * null when unknown or 0 (show nothing).
     */
    fun minutes(seconds: Long?): String? = when {
        seconds == null || seconds <= 0 -> null
        seconds < 60 -> "< 1 min"
        else -> "${seconds / 60} min"
    }

    fun rating(r: Double?): String? = r?.takeIf { it > 0 }?.let {
        // Xtream uses a 0–10 scale; some servers send 0–5.
        String.format(Locale.ITALY, "%.1f", if (it <= 5.0) it * 2 else it)
    }
}
