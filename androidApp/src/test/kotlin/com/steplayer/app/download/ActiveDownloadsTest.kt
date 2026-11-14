package com.steplayer.app.download

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDownloadsTest {
    private val registry = ActiveDownloads()

    @Test fun reportsOnlyWhileActive() {
        val job = Job()
        registry.register("1:MOVIE:a", job)
        assertEquals(1, registry.whileActive("1:MOVIE:a", job) { 1 })
        registry.stop("1:MOVIE:a")
        assertTrue(job.isCancelled)
        // A late progress callback after pause must not write anything.
        assertNull(registry.whileActive("1:MOVIE:a", job) { 2 })
    }

    @Test fun newTransferReplacesOldOne() {
        val old = Job()
        val new = Job()
        registry.register("x", old)
        registry.register("x", new)
        assertTrue(old.isCancelled)
        assertFalse(new.isCancelled)
        assertNull(registry.whileActive("x", old) { 1 })
        assertEquals(1, registry.whileActive("x", new) { 1 })
        // The old transfer finishing must not unregister the new one.
        registry.unregister("x", old)
        assertEquals(1, registry.whileActive("x", new) { 1 })
    }

    @Test fun stopUnknownIsNoOp() {
        registry.stop("missing")
        val job = Job()
        assertNull(registry.whileActive("missing", job) { 1 })
    }
}
