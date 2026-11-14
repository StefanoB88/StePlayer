package com.steplayer.shared.reminders

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.Source
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A local notification the platform should schedule (Android WorkManager / iOS UNUserNotificationCenter). */
data class ExpiryReminder(
    /** Stable identifier: scheduling the same id again must REPLACE the previous one. */
    val id: String,
    val sourceId: Long,
    val fireAtMs: Long,
    val title: String,
    val body: String,
)

/** In-app banner state for a list that expires soon or has expired. */
data class ExpiryNotice(
    val sourceId: Long,
    val sourceName: String,
    val expiresAtMs: Long,
    val daysLeft: Int,
    val expired: Boolean,
    val message: String,
)

/**
 * Reminders before an Xtream subscription expires (3 days and 1 day before), based on the
 * `exp_date` the provider reports at login. Neutral wording on purpose: StePlayer never points
 * to sellers or renewal offers.
 */
class ExpiryReminders internal constructor(
    private val db: StePlayerDatabase,
    private val sources: SourceRepository,
) {
    private val q get() = db.schemaQueries

    val enabled: CFlow<Boolean> = q.getSetting(KEY_ENABLED).asFlow().mapToOneOrNull(Dispatchers.IO)
        .map { it != "0" }.wrap()

    @Throws(Throwable::class)
    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        q.putSetting(KEY_ENABLED, if (value) "1" else "0")
    }

    /** Banners for the home screen: lists expiring within 3 days or already expired. */
    val notices: CFlow<List<ExpiryNotice>> = combine(sources.sources, enabled) { list, on ->
        if (!on) emptyList() else noticesFor(list, nowMillis())
    }.wrap()

    /**
     * Full set of reminders that should currently be scheduled. The platform must cancel every
     * previously scheduled expiry reminder and schedule exactly these (call after every sync,
     * list change, toggle change and on app start).
     * @param localUtcOffsetSec the device's current UTC offset, used to fire at 10:00 local time.
     */
    @Throws(Throwable::class)
    suspend fun pendingReminders(localUtcOffsetSec: Long): List<ExpiryReminder> {
        val on = withContext(Dispatchers.IO) { q.getSetting(KEY_ENABLED).executeAsOneOrNull() != "0" }
        if (!on) return emptyList()
        return remindersFor(sources.sources.first(), nowMillis(), localUtcOffsetSec)
    }

    companion object {
        private const val KEY_ENABLED = "expiry_reminders_enabled"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val REMIND_HOUR_LOCAL = 10

        internal fun remindersFor(list: List<Source>, now: Long, localUtcOffsetSec: Long): List<ExpiryReminder> =
            list.flatMap { s ->
                val exp = s.expiresAt ?: return@flatMap emptyList()
                if (exp <= now) return@flatMap emptyList()
                listOf(3, 1).mapNotNull { days ->
                    val target = atLocalHour(exp - days * DAY, localUtcOffsetSec)
                    val fireAt = when {
                        target > now -> target
                        // We're already past that reminder slot: only the most urgent one fires, right away.
                        days == 1 || exp - now > DAY -> now + 60_000
                        else -> return@mapNotNull null
                    }
                    if (fireAt >= exp) return@mapNotNull null
                    ExpiryReminder(
                        id = "expiry.${s.id}.$days",
                        sourceId = s.id,
                        fireAtMs = fireAt,
                        title = if (days == 1) "La lista scade domani" else "La lista scade tra 3 giorni",
                        body = "L'abbonamento «${s.name}» risulta in scadenza il ${formatDate(exp, localUtcOffsetSec)}.",
                    )
                }.distinctBy { it.fireAtMs / 120_000 } // never two notifications at the same moment
            }

        internal fun noticesFor(list: List<Source>, now: Long): List<ExpiryNotice> = list.mapNotNull { s ->
            val exp = s.expiresAt ?: return@mapNotNull null
            val left = exp - now
            if (left > 3 * DAY) return@mapNotNull null
            val days = if (left <= 0) 0 else ((left + DAY - 1) / DAY).toInt()
            ExpiryNotice(
                sourceId = s.id, sourceName = s.name, expiresAtMs = exp, daysLeft = days, expired = left <= 0,
                message = when {
                    left <= 0 -> "L'abbonamento «${s.name}» è scaduto."
                    days <= 1 -> "L'abbonamento «${s.name}» scade entro 24 ore."
                    else -> "L'abbonamento «${s.name}» scade tra $days giorni."
                },
            )
        }

        /** Same calendar day as [ms] (in local time), at 10:00 local. */
        private fun atLocalHour(ms: Long, offsetSec: Long): Long {
            val localDayStart = Math.floorDiv(ms / 1000 + offsetSec, 86_400L) * 86_400L
            return (localDayStart + REMIND_HOUR_LOCAL * 3600L - offsetSec) * 1000L
        }

        private fun formatDate(ms: Long, offsetSec: Long): String {
            val d = com.steplayer.shared.util.CivilTime.fromEpochSeconds(ms / 1000, offsetSec)
            return "${d.day.toString().padStart(2, '0')}/${d.month.toString().padStart(2, '0')}/${d.year}"
        }
    }
}

private object Math {
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
    }
}
