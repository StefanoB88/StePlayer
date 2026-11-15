package com.steplayer.shared

import com.steplayer.shared.util.ArtworkUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkUrlTest {
    private val original = "https://image.tmdb.org/t/p/original/abc123.jpg"

    @Test fun picksSmallestVariantCoveringTheSlot() {
        assertEquals("https://image.tmdb.org/t/p/w300/abc123.jpg", ArtworkUrl.sized(original, 300))
        assertEquals("https://image.tmdb.org/t/p/w342/abc123.jpg", ArtworkUrl.sized(original, 301))
        assertEquals("https://image.tmdb.org/t/p/w500/abc123.jpg", ArtworkUrl.sized(original, 343))
        assertEquals("https://image.tmdb.org/t/p/w1280/abc123.jpg", ArtworkUrl.sized(original, 1100))
        assertEquals("http://image.tmdb.org/t/p/w185/x.png", ArtworkUrl.sized("http://image.tmdb.org/t/p/original/x.png", 160))
    }

    @Test fun neverUpscalesAndKeepsOriginalForHugeSlots() {
        val w185 = "https://image.tmdb.org/t/p/w185/abc123.jpg"
        assertEquals(w185, ArtworkUrl.sized(w185, 600))
        assertEquals("https://image.tmdb.org/t/p/w92/abc123.jpg", ArtworkUrl.sized(w185, 80))
        assertEquals(original, ArtworkUrl.sized(original, 1920))
    }

    @Test fun otherUrlsAndUnknownSizeUntouched() {
        assertEquals(original, ArtworkUrl.sized(original, 0))
        val other = "http://server.esempio:8080/images/poster/1.jpg"
        assertEquals(other, ArtworkUrl.sized(other, 300))
        val query = "https://image.tmdb.org/t/p/original/abc.jpg?v=2"
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg?v=2", ArtworkUrl.sized(query, 320))
    }
}
