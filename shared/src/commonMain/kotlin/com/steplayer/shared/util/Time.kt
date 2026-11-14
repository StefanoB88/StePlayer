package com.steplayer.shared.util

import kotlin.time.Clock

internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Calendar fields in some fixed offset (no DST knowledge needed for our use cases). */
data class CivilDateTime(
    val year: Int, val month: Int, val day: Int,
    val hour: Int, val minute: Int, val second: Int,
)

object CivilTime {
    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }

    fun toEpochSeconds(dt: CivilDateTime, offsetSec: Long = 0): Long =
        daysFromCivil(dt.year, dt.month, dt.day) * 86400L +
            dt.hour * 3600L + dt.minute * 60L + dt.second - offsetSec

    fun fromEpochSeconds(epochSec: Long, offsetSec: Long = 0): CivilDateTime {
        val local = epochSec + offsetSec
        val days = floorDiv(local, 86400L)
        val secOfDay = local - days * 86400L
        val (y, m, d) = civilFromDays(days)
        return CivilDateTime(y, m, d, (secOfDay / 3600).toInt(), ((secOfDay % 3600) / 60).toInt(), (secOfDay % 60).toInt())
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
    }

    /**
     * Parses XMLTV timestamps: "20240115183000 +0100", "20240115183000", "202401151830 -0500".
     * Returns epoch millis or null.
     */
    fun parseXmltv(value: String): Long? {
        val s = value.trim()
        if (s.length < 12) return null
        fun num(from: Int, len: Int): Int? =
            if (from + len <= s.length) s.substring(from, from + len).toIntOrNull() else null
        val year = num(0, 4) ?: return null
        val month = num(4, 2) ?: return null
        val day = num(6, 2) ?: return null
        val hour = num(8, 2) ?: return null
        val minute = num(10, 2) ?: return null
        val second = if (s.length >= 14 && s[12].isDigit()) num(12, 2) ?: 0 else 0
        var offset = 0L
        val tzStart = s.indexOfAny(charArrayOf('+', '-'), startIndex = 12)
        if (tzStart > 0) {
            val sign = if (s[tzStart] == '-') -1 else 1
            val tz = s.substring(tzStart + 1).filter { it.isDigit() }
            if (tz.length >= 4) {
                val h = tz.substring(0, 2).toIntOrNull() ?: 0
                val m = tz.substring(2, 4).toIntOrNull() ?: 0
                offset = sign * (h * 3600L + m * 60L)
            }
        }
        return toEpochSeconds(CivilDateTime(year, month, day, hour, minute, second), offset) * 1000L
    }

    /** Parses "2024-01-15 18:30:00" interpreted at the given offset. */
    fun parseSqlDateTime(value: String, offsetSec: Long = 0): Long? {
        val parts = value.trim().split(' ', 'T')
        if (parts.isEmpty()) return null
        val date = parts[0].split('-').mapNotNull { it.toIntOrNull() }
        if (date.size != 3) return null
        val time = parts.getOrNull(1)?.split(':')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        return toEpochSeconds(
            CivilDateTime(date[0], date[1], date[2], time.getOrElse(0) { 0 }, time.getOrElse(1) { 0 }, time.getOrElse(2) { 0 }),
            offsetSec,
        ) * 1000L
    }
}

internal fun Int.pad2(): String = if (this < 10) "0$this" else toString()
