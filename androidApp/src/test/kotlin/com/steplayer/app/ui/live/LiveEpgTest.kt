package com.steplayer.app.ui.live

import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ChannelWithEpg
import com.steplayer.shared.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveEpgTest {
    private val now = 1_000_000_000L
    private fun ch(i: Int) = Channel(1, "c$i", i, "Canale $i", null, null, "e$i", 0)
    private fun prog(start: Long, end: Long) = EpgProgram("e", start, end, "Programma", null)

    @Test fun waitsUntilEarliestBoundary() {
        val list = listOf(
            ChannelWithEpg(ch(1), prog(now - 60_000, now + 120_000), prog(now + 120_000, now + 600_000)),
            ChannelWithEpg(ch(2), prog(now - 60_000, now + 60_000), null),
        )
        assertEquals(now + 61_000, nextEpgChange(list, now))
    }

    @Test fun noGuideRefreshesEveryFiveMinutes() {
        val list = listOf(ChannelWithEpg(ch(1), null, null))
        assertEquals(now + 300_000, nextEpgChange(list, now))
        assertEquals(now + 300_000, nextEpgChange(emptyList(), now))
    }

    @Test fun clampsToMinimumAndMaximum() {
        val soon = listOf(ChannelWithEpg(ch(1), prog(now - 10, now + 100), null))
        assertEquals(now + 5_000, nextEpgChange(soon, now))
        val far = listOf(ChannelWithEpg(ch(1), prog(now - 10, now + 3_600_000), null))
        assertEquals(now + 300_000, nextEpgChange(far, now))
    }

    @Test fun ignoresPastBoundaries() {
        // A programme that already ended (stale row) must not cause a tight re-query loop.
        val list = listOf(ChannelWithEpg(ch(1), prog(now - 100_000, now - 1), prog(now + 200_000, now + 400_000)))
        assertEquals(now + 201_000, nextEpgChange(list, now))
    }
}
