package com.steplayer.shared

import com.steplayer.shared.playback.BufferAdvisor
import com.steplayer.shared.playback.BufferProfile
import com.steplayer.shared.playback.SessionState
import com.steplayer.shared.playback.StopReason
import com.steplayer.shared.playback.TrackInfo
import com.steplayer.shared.playback.Tracks
import com.steplayer.shared.playback.WatchSessionTimer
import com.steplayer.shared.settings.BufferMode
import com.steplayer.shared.settings.PlayerSettings
import com.steplayer.shared.settings.SubtitleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSessionTimerTest {
    private val h = 3_600_000L

    @Test fun stillWatchingPromptThenStop() {
        val t = WatchSessionTimer(stillWatchingAfterHours = 4)
        t.start(0)
        assertEquals(SessionState.Running, t.tick(4 * h - 1))
        assertEquals(SessionState.AskStillWatching(60), t.tick(4 * h))
        t.onInteraction(4 * h + 10_000) // "Sì, sto guardando"
        assertEquals(SessionState.Running, t.tick(4 * h + 20_000))
        assertEquals(SessionState.Stop(StopReason.INACTIVITY), t.tick(8 * h + 10_000 + 60_000))
    }

    @Test fun disabledNeverAsks() {
        val t = WatchSessionTimer(stillWatchingAfterHours = 0)
        t.start(0)
        assertEquals(SessionState.Running, t.tick(48 * h))
    }

    @Test fun sleepTimerWarnsExtendsAndStops() {
        val t = WatchSessionTimer(stillWatchingAfterHours = 0)
        t.start(0)
        t.setSleepTimer(30, 0)
        assertEquals(SessionState.Running, t.tick(29 * 60_000L - 1))
        assertEquals(SessionState.SleepWarning(60), t.tick(29 * 60_000L))
        t.extendSleep(15, 29 * 60_000L)
        assertEquals(SessionState.Running, t.tick(31 * 60_000L))
        assertIs<SessionState.Stop>(t.tick(45 * 60_000L))
        assertNull(t.sleepRemainingMs(46 * 60_000L)) // one-shot
    }
}

class BufferAdvisorTest {
    @Test fun autoEscalatesAfterTwoStallsInTenMinutes() {
        val b = BufferAdvisor(BufferMode.AUTO)
        assertEquals("Normale", b.profile().name)
        assertFalse(b.onStall(0))
        assertFalse(b.onStall(11 * 60_000L)) // first stall expired
        assertTrue(b.onStall(12 * 60_000L))
        assertEquals("Grande", b.profile().name)
        assertFalse(b.onStall(13 * 60_000L)) // already escalated
        assertEquals("Grande", BufferAdvisor(BufferMode.LARGE).profile().name)
        assertFalse(BufferAdvisor(BufferMode.NORMAL).onStall(0))
    }

    @Test fun largeKeepsBigReserveButStartsFast() {
        val large = BufferAdvisor(BufferMode.LARGE).profile()
        assertEquals(120_000, large.maxBufferMs)
        assertEquals(30_000, large.minBufferMs)
        assertEquals(2_000, large.bufferForPlaybackMs)
        assertEquals(5_000, large.bufferForPlaybackAfterRebufferMs)
        // ExoPlayer requires bufferForPlayback(AfterRebuffer) <= minBuffer.
        assertTrue(large.bufferForPlaybackAfterRebufferMs <= large.minBufferMs)
        val normal = BufferAdvisor(BufferMode.NORMAL).profile()
        assertEquals(BufferProfile("Normale", 15_000, 50_000, 1_500, 3_000, 8_000, 0.0), normal)
    }
}

class TracksTest {
    private val ita = TrackInfo("a1", "ita", null, 6)
    private val eng = TrackInfo("a2", "en", "English", 2, isDefault = true)
    private val subIt = TrackInfo("s1", "it-IT", null)
    private val subItForced = TrackInfo("s2", "Italian", null, isForced = true)
    private val subEn = TrackInfo("s3", "eng", null)

    @Test fun normalizationAndNames() {
        assertEquals("it", Tracks.normalize("ITA")); assertEquals("it", Tracks.normalize("Italiano"))
        assertEquals("de", Tracks.normalize("ger")); assertNull(Tracks.normalize("und"))
        assertEquals("Italiano · 5.1", Tracks.displayName(ita, 0))
        assertEquals("Traccia 3", Tracks.displayName(TrackInfo("x", null, null), 2))
    }

    @Test fun audioFollowsPreferencesThenDevice() {
        assertEquals("a1", Tracks.pickAudio(listOf(eng, ita), PlayerSettings(), listOf("it-IT"))!!.id)
        assertEquals("a2", Tracks.pickAudio(listOf(eng, ita), PlayerSettings(preferredAudioLanguages = listOf("en")), listOf("it"))!!.id)
        assertEquals("a2", Tracks.pickAudio(listOf(ita, eng), PlayerSettings(), listOf("fr"))!!.id) // default flag
    }

    @Test fun subtitleModes() {
        val subs = listOf(subEn, subItForced, subIt)
        val auto = PlayerSettings(subtitleMode = SubtitleMode.AUTO)
        // Italian user, English audio → full Italian subtitles
        assertEquals("s1", Tracks.pickSubtitle(subs, eng, auto, listOf("it"))!!.id)
        // Italian audio → only forced subtitles
        assertEquals("s2", Tracks.pickSubtitle(subs, ita, auto, listOf("it"))!!.id)
        assertNull(Tracks.pickSubtitle(listOf(subEn, subIt), ita, auto, listOf("it")))
        assertEquals("s1", Tracks.pickSubtitle(subs, ita, PlayerSettings(subtitleMode = SubtitleMode.ALWAYS), listOf("it"))!!.id)
        assertNull(Tracks.pickSubtitle(subs, eng, PlayerSettings(subtitleMode = SubtitleMode.OFF), listOf("it")))
    }
}
