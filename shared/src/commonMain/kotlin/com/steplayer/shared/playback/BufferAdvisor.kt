package com.steplayer.shared.playback

import com.steplayer.shared.settings.BufferMode

/** Concrete buffer sizes for the platform players (ExoPlayer LoadControl / AVPlayer forward buffer). */
data class BufferProfile(
    val name: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    /** Target distance from the live edge for HLS live. */
    val liveTargetOffsetMs: Int,
    /** AVPlayer preferredForwardBufferDuration in seconds (0 = system automatic). */
    val appleForwardBufferSec: Double,
)

/**
 * Picks the buffer profile. In AUTO mode it starts small (fast zapping) and switches to the
 * large profile after repeated stalls, until the app restarts. Read [profile] at every stream start.
 */
class BufferAdvisor(private val mode: BufferMode) {
    private val stalls = ArrayList<Long>()
    private var escalated = false

    fun profile(): BufferProfile = when {
        mode == BufferMode.LARGE || (mode == BufferMode.AUTO && escalated) -> LARGE
        else -> NORMAL
    }

    /** Report every stall / rebuffer > 1 s. Returns true if the profile just changed (restart the stream to apply). */
    fun onStall(nowMs: Long): Boolean {
        if (mode != BufferMode.AUTO || escalated) return false
        stalls += nowMs
        stalls.removeAll { nowMs - it > WINDOW_MS }
        if (stalls.size >= STALLS_TO_ESCALATE) {
            escalated = true
            return true
        }
        return false
    }

    val isEscalated: Boolean get() = escalated

    companion object {
        private const val WINDOW_MS = 10 * 60_000L
        private const val STALLS_TO_ESCALATE = 2

        val NORMAL = BufferProfile("Normale", 15_000, 50_000, 1_500, 3_000, 8_000, 0.0)
        /** Big reserve once playing, but starts as fast as NORMAL-ish (2 s / 5 s after a rebuffer). */
        val LARGE = BufferProfile("Grande", 30_000, 120_000, 2_000, 5_000, 20_000, 60.0)
    }
}
