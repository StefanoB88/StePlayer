package com.steplayer.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SpeedInfo(val bytesPerSecond: Long, val etaSeconds: Long?, val connections: Int)

/** Live transfer speed per download (in memory only), for the Downloads screen and notifications. */
object DownloadSpeeds {
    private val _state = MutableStateFlow<Map<String, SpeedInfo>>(emptyMap())
    val state: StateFlow<Map<String, SpeedInfo>> = _state.asStateFlow()

    fun set(id: String, info: SpeedInfo) = _state.update { it + (id to info) }
    fun clear(id: String) = _state.update { it - id }
}

/** Exponentially smoothed throughput (stable numbers instead of jumpy per-second values). */
internal class SpeedMeter {
    private var lastBytes = -1L
    private var lastAt = 0L
    private var smoothed = 0.0

    fun sample(bytes: Long, nowMs: Long): Long {
        if (lastBytes >= 0 && nowMs > lastAt) {
            val instant = (bytes - lastBytes) * 1000.0 / (nowMs - lastAt)
            smoothed = if (smoothed == 0.0) instant else smoothed * 0.7 + instant * 0.3
        }
        lastBytes = bytes
        lastAt = nowMs
        return smoothed.toLong().coerceAtLeast(0)
    }
}
