package com.steplayer.shared

import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.reminders.ExpiryReminders
import com.steplayer.shared.util.CivilDateTime
import com.steplayer.shared.util.CivilTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpiryRemindersTest {
    private val day = 86_400_000L
    private val rome = 3600L // UTC+1
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        CivilTime.toEpochSeconds(CivilDateTime(y, m, d, h, min, 0)) * 1000
    private fun src(exp: Long?) = Source(7, "Mio IPTV", SourceType.XTREAM, "http://x", "u", null, null, 0, emptyList(), exp, 1, null, null)

    @Test fun schedulesThreeAndOneDayBeforeAt10Local() {
        val exp = utc(2026, 12, 20, 2, 30) // 03:30 in Rome on the 20th
        val r = ExpiryReminders.remindersFor(listOf(src(exp)), now = utc(2026, 12, 1, 12), localUtcOffsetSec = rome)
        assertEquals(listOf("expiry.7.3", "expiry.7.1"), r.map { it.id })
        assertEquals(utc(2026, 12, 17, 9), r[0].fireAtMs) // 10:00 Rome
        assertEquals(utc(2026, 12, 19, 9), r[1].fireAtMs)
        assertTrue(r[0].body.contains("20/12/2026"))
        assertEquals("La lista scade domani", r[1].title)
    }

    @Test fun lateInstallOnlyFiresWhatStillMakesSense() {
        val exp = utc(2026, 12, 20, 18)
        // 2 days before: the 3-day slot is gone → immediate notice, plus the 1-day one later
        val r = ExpiryReminders.remindersFor(listOf(src(exp)), now = exp - 2 * day, localUtcOffsetSec = rome)
        assertEquals(2, r.size)
        assertEquals(exp - 2 * day + 60_000, r[0].fireAtMs)
        // 5 hours before: single immediate reminder
        val late = ExpiryReminders.remindersFor(listOf(src(exp)), now = exp - 5 * 3_600_000, localUtcOffsetSec = rome)
        assertEquals(listOf("expiry.7.1"), late.map { it.id })
        // Expired or unknown expiry: nothing
        assertTrue(ExpiryReminders.remindersFor(listOf(src(exp), src(null)), now = exp + 1, localUtcOffsetSec = rome).isEmpty())
    }

    @Test fun homeNotices() {
        val now = utc(2026, 12, 1, 12)
        assertTrue(ExpiryReminders.noticesFor(listOf(src(now + 10 * day)), now).isEmpty())
        assertEquals(2, ExpiryReminders.noticesFor(listOf(src(now + 2 * day - 1000)), now).single().daysLeft)
        val expired = ExpiryReminders.noticesFor(listOf(src(now - 1)), now).single()
        assertTrue(expired.expired); assertEquals("L'abbonamento «Mio IPTV» è scaduto.", expired.message)
    }
}
