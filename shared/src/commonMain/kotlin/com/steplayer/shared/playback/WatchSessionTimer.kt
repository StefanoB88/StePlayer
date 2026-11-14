package com.steplayer.shared.playback

sealed interface SessionState {
    data object Running : SessionState
    /** Show "Stai ancora guardando?" with a countdown; any answer/interaction → onInteraction(). */
    data class AskStillWatching(val secondsLeft: Int) : SessionState
    /** Last minute of the sleep timer: "Spegnimento tra N s" with "+15 min" / "Annulla". */
    data class SleepWarning(val secondsLeft: Int) : SessionState
    /** Stop playback NOW and release the connection. */
    data class Stop(val reason: StopReason) : SessionState
}

enum class StopReason { INACTIVITY, SLEEP_TIMER }

/**
 * Keeps abandoned players from streaming (and holding the subscription's connection) all night.
 * Feed it user interactions and tick it every second from the player.
 */
class WatchSessionTimer(
    stillWatchingAfterHours: Int,
    private val promptCountdownMs: Long = 60_000,
    private val sleepWarningMs: Long = 60_000,
) {
    private val inactivityMs: Long = stillWatchingAfterHours * 3_600_000L
    private var lastInteraction = -1L
    private var sleepAt: Long? = null

    /** Remaining sleep-timer time in ms, or null if no timer is set (for the settings label). */
    fun sleepRemainingMs(nowMs: Long): Long? = sleepAt?.let { (it - nowMs).coerceAtLeast(0) }

    fun start(nowMs: Long) { lastInteraction = nowMs }

    /** Any remote key, touch, zap or menu action. */
    fun onInteraction(nowMs: Long) { lastInteraction = nowMs }

    fun setSleepTimer(minutes: Int, nowMs: Long) { sleepAt = nowMs + minutes * 60_000L }

    /** "Fine programma": stop when the current EPG programme ends. */
    fun setSleepAt(epochMs: Long) { sleepAt = epochMs }

    fun extendSleep(minutes: Int, nowMs: Long) {
        sleepAt = maxOf(sleepAt ?: nowMs, nowMs) + minutes * 60_000L
    }

    fun cancelSleep() { sleepAt = null }

    fun tick(nowMs: Long): SessionState {
        if (lastInteraction < 0) lastInteraction = nowMs
        sleepAt?.let { at ->
            if (nowMs >= at) { sleepAt = null; return SessionState.Stop(StopReason.SLEEP_TIMER) }
            if (at - nowMs <= sleepWarningMs) return SessionState.SleepWarning(((at - nowMs + 999) / 1000).toInt())
        }
        if (inactivityMs > 0) {
            val idle = nowMs - lastInteraction
            if (idle >= inactivityMs + promptCountdownMs) return SessionState.Stop(StopReason.INACTIVITY)
            if (idle >= inactivityMs) {
                return SessionState.AskStillWatching(((inactivityMs + promptCountdownMs - idle + 999) / 1000).toInt())
            }
        }
        return SessionState.Running
    }
}
