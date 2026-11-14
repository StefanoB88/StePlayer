package com.steplayer.app.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.ChipItem
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.components.rememberNow
import com.steplayer.app.ui.components.pressable
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.app.util.Format
import com.steplayer.shared.StePlayerCore
import com.steplayer.shared.data.Categories
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ChannelWithEpg
import com.steplayer.shared.model.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class LiveState(
    val sourceId: Long = -1,
    val chips: List<ChipItem> = emptyList(),
    val selected: String? = null,
    val channels: List<ChannelWithEpg>? = null,
    val favorites: Set<String> = emptySet(),
)

class LiveViewModel(private val app: StePlayerApp, private val labels: Pair<String, String>) : ViewModel() {
    private val core = app.core
    private val selected = MutableStateFlow<String?>(null)

    private val source = core.sources.activeSource.map { it.id }.distinctUntilChanged()

    private val chips: Flow<List<ChipItem>> = source.flatMapLatest { id ->
        if (id < 0) flowOf(emptyList())
        else core.catalog.categories(id, ContentType.LIVE).map { cats ->
            listOf(ChipItem(Categories.FAVORITES, labels.first), ChipItem(Categories.ALL, labels.second)) +
                cats.map { ChipItem(it.id, it.name) }
        }
    }

    private val effective: Flow<Pair<Long, String?>> = combine(source, chips, selected) { id, c, sel ->
        id to (sel?.takeIf { s -> c.any { it.id == s } } ?: c.getOrNull(2)?.id ?: c.getOrNull(1)?.id)
    }.distinctUntilChanged()

    private val channels: Flow<List<ChannelWithEpg>?> = effective.flatMapLatest { (id, cat) ->
        if (id < 0 || cat == null) flowOf(emptyList())
        else core.catalog.channels(id, cat).distinctUntilChanged()
            .flatMapLatest { list -> core.nowNextLive(id, list) }
            .map<List<ChannelWithEpg>, List<ChannelWithEpg>?> { it }.onStart { emit(null) }
    }

    private val favorites = source.flatMapLatest { id -> if (id < 0) flowOf(emptySet()) else core.library.favoriteIds(id, ContentType.LIVE) }

    // Big lists (thousands of channels): build/compare them off the main thread.
    val state: StateFlow<LiveState> = combine(source, chips, effective, channels, favorites) { id, c, eff, ch, fav ->
        LiveState(id, c, eff.second, ch, fav)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveState())

    fun select(id: String) { selected.value = id }

    fun toggleFavorite(channel: Channel, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        runCatching { core.library.toggleFavorite(channel.sourceId, ContentType.LIVE, channel.id) }.onSuccess(onResult)
    }
}

@Composable
fun LiveScreen(actions: AppActions) {
    val context = LocalContext.current
    val labels = stringResource(R.string.cat_favorites) to stringResource(R.string.cat_all)
    val vm = appViewModel { LiveViewModel(it, labels) }
    val state by vm.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.nav_live), large = true) {
            RoundIconButton(Icons.Rounded.CalendarViewWeek, stringResource(R.string.tv_guide), {
                val cat = state.selected
                if (state.sourceId >= 0 && cat != null) actions.openGuide(state.sourceId, cat)
            })
            if (!isTv) {
                RoundIconButton(Icons.Rounded.Search, stringResource(R.string.nav_search), actions::openSearch)
                RoundIconButton(Icons.Rounded.Settings, stringResource(R.string.nav_settings), actions::openSettings)
            }
        }
        // Live only: categories in a vertical column on the left, channels on the right.
        val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
        Row(Modifier.fillMaxSize()) {
            CategoryRail(
                items = state.chips,
                selected = state.selected,
                onSelect = vm::select,
                modifier = Modifier.width(if (isTv) 240.dp else if (wide) 210.dp else 118.dp).fillMaxHeight(),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val list = state.channels
                when {
                    list == null -> LoadingBox()
                    list.isEmpty() -> EmptyState(Icons.Rounded.LiveTv, stringResource(R.string.empty_channels))
                    else -> {
                        val listState = rememberLazyListState()
                        val selected = state.selected
                        val favorites = state.favorites
                        // One clock for the whole list: ticks only redraw the progress bars.
                        val now = rememberNow()
                        // New category → back to the top of its channel list.
                        LaunchedEffect(selected) { listState.scrollToItem(0) }
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(list, key = { it.channel.id }, contentType = { "channel" }) { item ->
                                ChannelRow(
                                    item = item,
                                    favorite = item.channel.id in favorites,
                                    now = now,
                                    onClick = { actions.playChannel(item.channel, selected) },
                                    onLongClick = {
                                        vm.toggleFavorite(item.channel) { fav ->
                                            context.toast(context.getString(if (fav) R.string.added_favorite else R.string.removed_favorite))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vertical, scrollable category list (Live section). The selected category has an accent bar
 * and tinted background; on TV it's reachable with D-pad left from the channel list.
 */
@Composable
private fun CategoryRail(items: List<ChipItem>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val railState = rememberLazyListState()
    // Keep the selected category visible (e.g. when coming back to the screen).
    LaunchedEffect(items.isNotEmpty()) {
        val index = items.indexOfFirst { it.id == selected }
        if (index > 0) railState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    LazyColumn(
        modifier.background(Palette.Surface.copy(alpha = 0.5f)),
        state = railState,
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.id }, contentType = { "category" }) { item ->
            val isSelected = item.id == selected
            val shape = LogoShape
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusHighlight(shape, 1.03f)
                    .clip(shape)
                    .background(if (isSelected) Palette.Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(item.id) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) Palette.Accent else androidx.compose.ui.graphics.Color.Transparent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Palette.TextPrimary else Palette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

// Shared shapes: no per-row allocation while scrolling thousands of channels.
private val RowShape = RoundedCornerShape(14.dp)
private val LogoShape = RoundedCornerShape(10.dp)

@Composable
fun ChannelRow(item: ChannelWithEpg, favorite: Boolean, now: State<Long>, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ch = item.channel
    val shape = RowShape
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(shape, 1.02f)
            .clip(shape)
            .background(Palette.Surface)
            .pressable(onClick, onLongClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 76.dp, height = 52.dp).clip(LogoShape).background(Palette.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            ArtImage(ch.logo, ch.name, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch.num != null) {
                    Text("${ch.num}", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ch.archiveDays > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.History, stringResource(R.string.catchup), tint = Palette.Accent2, modifier = Modifier.size(14.dp))
                }
            }
            val cur = item.now
            if (cur != null) {
                Spacer(Modifier.height(2.dp))
                Text(cur.title, style = MaterialTheme.typography.bodySmall, color = Palette.TextPrimary.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                ProgressBar({ cur.progress(now.value) }, Modifier.fillMaxWidth(0.9f), height = 3.dp)
                item.next?.let { n ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Format.clock(n.startAt)}  ${n.title}",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(stringResource(R.string.no_epg), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (favorite) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Star, stringResource(R.string.favorite), tint = Palette.Accent, modifier = Modifier.size(18.dp))
        }
    }
}
