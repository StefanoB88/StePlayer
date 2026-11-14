package com.steplayer.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test fun minutesNeverShowsZero() {
        assertNull(Format.minutes(null))
        assertNull(Format.minutes(0))
        assertEquals("< 1 min", Format.minutes(1))
        assertEquals("< 1 min", Format.minutes(59))
        assertEquals("1 min", Format.minutes(60))
        assertEquals("45 min", Format.minutes(2700))
    }

    @Test fun remainingIsCompact() {
        assertEquals("40 s", Format.remaining(40))
        assertEquals("3 min", Format.remaining(150))
        assertEquals("1 h 05 min", Format.remaining(3900))
    }

    @Test fun bytesUseOneDecimalForGigabytes() {
        assertEquals("1,2 GB", Format.bytes((1.2 * (1L shl 30)).toLong()))
        assertEquals("300 MB", Format.bytes(300L shl 20))
    }
}
