package com.steplayer.shared

import com.steplayer.shared.playback.TrackInfo
import com.steplayer.shared.playback.Tracks
import com.steplayer.shared.settings.PlayerSettings
import com.steplayer.shared.settings.SubtitleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackDedupeTest {
    @Test fun languageFromTagOrName() {
        assertEquals("hu", Tracks.languageOf(TrackInfo("1", "hun", null)))
        assertEquals("ko", Tracks.languageOf(TrackInfo("1", "kor", null)))
        assertEquals("tgk", Tracks.languageOf(TrackInfo("1", "tgk", null))) // unknown code kept for grouping
        assertEquals("it", Tracks.languageOf(TrackInfo("1", null, "Track 1 - [Italian]"))) // VLC names
        assertEquals("en", Tracks.languageOf(TrackInfo("1", null, "English")))
        assertEquals("it", Tracks.languageOf(TrackInfo("1", "und", "ITA AC3 5.1")))
        // Ordinary words are not languages: "per" (Persian), "cat" (Catalan) in lower case.
        assertNull(Tracks.languageOf(TrackInfo("1", null, "Commento per il cat")))
        assertNull(Tracks.languageOf(TrackInfo("1", null, null)))
    }

    @Test fun displayNamesWithRoles() {
        assertEquals("Ungherese", Tracks.displayName(TrackInfo("1", "hun", null), 0))
        assertEquals("Inglese · non udenti", Tracks.displayName(TrackInfo("1", "en", "English SDH"), 0))
        assertEquals("Italiano · commento", Tracks.displayName(TrackInfo("1", "it", "Commento del regista"), 0))
        assertEquals("Traccia 2", Tracks.displayName(TrackInfo("1", null, null), 1))
    }

    @Test fun oneEntryPerLanguageKeepingAll() {
        // HLS: the same Italian rendition in two codec groups, plus English.
        val hls = listOf(TrackInfo("a", "it", "Italiano", 2), TrackInfo("b", "en", "English", 2), TrackInfo("c", "ita", "Italiano", 6))
        assertEquals(listOf("c", "b"), Tracks.distinct(hls).map { it.id }) // 5.1 wins, both languages kept
        assertEquals(listOf("a", "b"), Tracks.distinct(hls, selectedId = "a").map { it.id }) // the track in use stays
        // Default flag beats channel count.
        val mkv = listOf(TrackInfo("1", "ita", null, 6), TrackInfo("2", "ita", null, 2, isDefault = true))
        assertEquals(listOf("2"), Tracks.distinct(mkv).map { it.id })
    }

    @Test fun variantsAndUnknownTracksStay() {
        val subs = listOf(
            TrackInfo("1", "en", "English"), TrackInfo("2", "en", "English SDH"), TrackInfo("3", "en", "English", isForced = true),
            TrackInfo("4", "en", "English"), TrackInfo("5", null, null), TrackInfo("6", null, null), TrackInfo("7", null, "Sub A"), TrackInfo("8", null, "Sub A"),
        )
        assertEquals(listOf("1", "2", "3", "5", "6", "7"), Tracks.distinct(subs).map { it.id })
    }

    @Test fun alwaysShowsSomethingWhenTheLanguageIsMissing() {
        val always = PlayerSettings(subtitleMode = SubtitleMode.ALWAYS, preferredSubtitleLanguage = "it")
        val subs = listOf(TrackInfo("en", "en", null), TrackInfo("fr", "fr", null, isDefault = true))
        assertEquals("fr", Tracks.pickSubtitle(subs, null, always, listOf("it"))!!.id) // default first
        assertEquals("x", Tracks.pickSubtitle(listOf(TrackInfo("x", null, null)), null, always, listOf("it"))!!.id) // unlabeled
        val withIt = subs + TrackInfo("itsdh", "it", "Italiano SDH") + TrackInfo("it", "it", null)
        assertEquals("it", Tracks.pickSubtitle(withIt, null, always, listOf("it"))!!.id) // plain before SDH
        // AUTO keeps its rule: nothing when the audio is already in the user's language.
        assertNull(Tracks.pickSubtitle(subs, TrackInfo("a", "it", null), PlayerSettings(subtitleMode = SubtitleMode.AUTO, preferredSubtitleLanguage = "it"), listOf("it")))
    }
}
