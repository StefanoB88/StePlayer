package com.steplayer.app.ui.live

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.player.PlayerLauncher
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.app.util.Format
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.EpgProgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Immutable
data class GuideState(
    val loading: Boolean = true,
    val from: Long = 0,
    val to: Long = 0,
    val channels: List<Channel> = emptyList(),
    val programs: Map<String, List<EpgProgram>> = emptyMap(),
)

class GuideViewModel(app: StePlayerApp, sourceId: Long, categoryId: String) : ViewModel() {
    private val _state = MutableStateFlow(GuideState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val halfHour = 30 * 60_000L
            val now = System.currentTimeMillis()
            val from = (now - 6 * 3_600_000L) / halfHour * halfHour
            val to = from + 30 * 3_600_000L
            // Never crash the screen on a DB/guide error: show what we have (or the empty state).
            val channels = runCatching { app.core.catalog.channels(sourceId, categoryId).first() }.getOrDefault(emptyList())
            val ids = channels.mapNotNull { it.epgId }.distinct()
            val programs = runCatching { withContext(Dispatchers.Default) { app.core.epg.guide(sourceId, ids, from, to) } }.getOrDefault(emptyMap())
            _state.value = GuideState(false, from, to, channels, programs)
        }
    }
}

private val RowHeight = 68.dp
private val ChannelColumn = 150.dp

@Composable
fun GuideScreen(sourceId: Long, categoryId: String, actions: AppActions) {
    val vm = appViewModel(key = "guide_${sourceId}_$categoryId") { GuideViewModel(it, sourceId, categoryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val perMinute: Dp = if (LocalIsTv.current) 6.dp else 5.dp
    val hScroll = rememberScrollState()
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.tv_guide), onBack = actions::back)
        when {
            state.loading -> LoadingBox()
            state.channels.isEmpty() -> EmptyState(Icons.Rounded.CalendarViewWeek, stringResource(R.string.empty_channels))
            else -> {
                val now = remember { System.currentTimeMillis() }
                val totalWidth = perMinute * ((state.to - state.from) / 60_000f)
                LaunchedEffect(state.from) {
                    val target = with(density) { (perMinute * ((now - state.from - 30 * 60_000L) / 60_000f)).toPx() }
                    hScroll.scrollTo(target.toInt().coerceAtLeast(0))
                }
                // Time ruler
                Row(Modifier.fillMaxWidth().height(36.dp)) {
                    Spacer(Modifier.width(ChannelColumn))
                    Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                        Box(Modifier.width(totalWidth).fillMaxHeight()) {
                            var t = state.from
                            while (t < state.to) {
                                val x = perMinute * ((t - state.from) / 60_000f)
                                Text(
                                    Format.clock(t),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Palette.TextSecondary,
                                    modifier = Modifier.offset(x = x + 6.dp).align(Alignment.CenterStart),
                                )
                                t += 30 * 60_000L
                            }
                            NowLine(perMinute * ((now - state.from) / 60_000f))
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.channels, key = { it.id }, contentType = { "row" }) { ch ->
                        GuideRow(
                            channel = ch,
                            programs = ch.epgId?.let { state.programs[it] }.orEmpty(),
                            from = state.from, now = now, perMinute = perMinute, totalWidth = totalWidth, hScroll = hScroll,
                            onChannel = { actions.playChannel(ch, categoryId) },
                            onProgram = { p ->
                                when {
                                    p.isLive(now) -> actions.playChannel(ch, categoryId)
                                    p.endAt <= now && ch.archiveDays > 0 &&
                                        p.startAt >= now - ch.archiveDays * 86_400_000L ->
                                        PlayerLauncher.catchup(context, ch.sourceId, ch.id, p.startAt, p.endAt, p.title)
                                    p.endAt <= now -> context.toast(context.getString(R.string.catchup_unavailable))
                                    else -> context.toast("${Format.dayTime(p.startAt)} · ${p.title}")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A programme that started before the visible part of the guide is laid out from
 * max(start, visible window start): its title/time slide along inside the visible part of the
 * block instead of staying at the true (off-screen) start. Applied after the block's padding,
 * so [blockX] + padding is where the text would normally start.
 *
 * Reads the scroll position in the layout phase only: scrolling re-places, never recomposes.
 */
private fun Modifier.stickToViewportStart(hScroll: ScrollState, blockX: Dp): Modifier = layout { measurable, constraints ->
    val hidden = (hScroll.value - blockX.roundToPx()).coerceAtLeast(0)
    // Keep at least a short label visible while the block's end scrolls out.
    val shift = hidden.coerceAtMost((constraints.maxWidth - MinLabelWidth.roundToPx()).coerceAtLeast(0))
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth - shift))
    layout(constraints.maxWidth, placeable.height) { placeable.place(shift, 0) }
}

private val MinLabelWidth = 40.dp

@Composable
private fun NowLine(x: Dp) {
    Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(Palette.Live))
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<EpgProgram>,
    from: Long,
    now: Long,
    perMinute: Dp,
    totalWidth: Dp,
    hScroll: ScrollState,
    onChannel: () -> Unit,
    onProgram: (EpgProgram) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 3.dp)) {
        Row(
            Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .padding(horizontal = 6.dp)
                .focusHighlight(RoundedCornerShape(10.dp), 1.03f)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Surface)
                .clickable(onClick = onChannel)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtImage(channel.logo, channel.name, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(8.dp))
            Text(channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                programs.forEach { p ->
                    val start = maxOf(p.startAt, from)
                    val x = perMinute * ((start - from) / 60_000f)
                    val w = (perMinute * ((p.endAt - start) / 60_000f) - 3.dp).coerceAtLeast(8.dp)
                    val live = p.isLive(now)
                    val past = p.endAt <= now
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier
                            .offset(x = x)
                            .width(w)
                            .fillMaxHeight()
                            .focusHighlight(shape, 1.02f)
                            .clip(shape)
                            .background(
                                when {
                                    live -> Palette.SurfaceLive
                                    past -> Palette.Surface.copy(alpha = 0.6f)
                                    else -> Palette.Surface
                                },
                            )
                            .clickable { onProgram(p) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .stickToViewportStart(hScroll, x),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (past && channel.archiveDays > 0) {
                                Icon(Icons.Rounded.History, null, tint = Palette.Accent2, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                p.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (past) Palette.TextSecondary else Palette.TextPrimary,
                            )
                        }
                        Text(
                            "${Format.clock(p.startAt)} – ${Format.clock(p.endAt)}",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1,
                        )
                    }
                }
                NowLine(perMinute * ((now - from) / 60_000f))
            }
        }
    }
}
