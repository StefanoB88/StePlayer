package com.steplayer.app.ui.live

import com.steplayer.shared.StePlayerCore
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ChannelWithEpg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Now/next for any number of channels at a fixed instant (chunked to respect SQLite's variable limit). */
fun StePlayerCore.nowNextChunked(sourceId: Long, channels: List<Channel>, now: Long): Flow<List<ChannelWithEpg>> {
    if (channels.isEmpty()) return flowOf(emptyList())
    if (channels.size <= CHUNK) return epg.nowNext(sourceId, channels, now)
    val flows = channels.chunked(CHUNK).map { epg.nowNext(sourceId, it, now) }
    return combine(flows) { parts -> parts.flatMap { it } }
}

/**
 * Live now/next that stays current: the channels without guide info are emitted once right away,
 * then the guide is re-queried only when a programme actually starts/ends (or every 5 minutes),
 * instead of re-querying thousands of channels every minute and flashing "no guide" in between.
 * Progress bars are driven by a separate clock ([com.steplayer.app.ui.components.rememberNow]).
 */
fun StePlayerCore.nowNextLive(sourceId: Long, channels: List<Channel>): Flow<List<ChannelWithEpg>> {
    if (channels.isEmpty()) return flowOf(emptyList())
    return channelFlow {
        send(channels.map { ChannelWithEpg(it, null, null) })
        val clock = MutableStateFlow(System.currentTimeMillis())
        clock.flatMapLatest { now -> nowNextChunked(sourceId, channels, now) }.collectLatest { list ->
            send(list)
            val now = System.currentTimeMillis()
            delay(nextEpgChange(list, now) - now)
            clock.value = System.currentTimeMillis()
        }
    }
}

/** When the now/next of [list] changes next: the earliest programme boundary after [now], kept within 5 s…5 min. */
internal fun nextEpgChange(list: List<ChannelWithEpg>, now: Long): Long {
    var next = Long.MAX_VALUE
    for (c in list) {
        c.now?.endAt?.let { if (it in (now + 1) until next) next = it }
        c.next?.startAt?.let { if (it in (now + 1) until next) next = it }
    }
    // +1 s so the boundary has really passed when the guide is queried again.
    return if (next == Long.MAX_VALUE) now + MAX_WAIT else (next + 1_000).coerceIn(now + MIN_WAIT, now + MAX_WAIT)
}

private const val CHUNK = 800
private const val MIN_WAIT = 5_000L
private const val MAX_WAIT = 300_000L
