package com.steplayer.app.ui.home

import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.ui.Tab
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.PosterCard
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.components.SectionHeader
import com.steplayer.app.ui.components.WideCard
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.Dimens
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import com.steplayer.shared.data.Categories
import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.Series
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.WatchProgress
import com.steplayer.shared.reminders.ExpiryNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import com.steplayer.app.ui.components.focusHighlight
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.steplayer.shared.model.SyncState
import kotlinx.coroutines.flow.map

@Immutable
data class HeroItem(val sourceId: Long, val id: String, val title: String, val image: String?, val subtitle: String?, val isSeries: Boolean)

@Immutable
data class HomeState(
    val loading: Boolean = true,
    val source: Source? = null,
    val sources: List<Source> = emptyList(),
    val hero: List<HeroItem> = emptyList(),
    val continueWatching: List<WatchProgress> = emptyList(),
    val recentChannels: List<WatchProgress> = emptyList(),
    val recentMovies: List<Movie> = emptyList(),
    val recentSeries: List<Series> = emptyList(),
) {
    val isEmpty get() = !loading && continueWatching.isEmpty() && recentChannels.isEmpty() && recentMovies.isEmpty() && recentSeries.isEmpty()
}

class HomeViewModel(private val app: StePlayerApp) : ViewModel() {
    private val core = app.core

    val state: StateFlow<HomeState> = combine(core.sources.activeSource, core.sources.sources) { a, b -> a to b }
        .flatMapLatest { (src, all) ->
            if (src.id == SourceRepository.NO_SOURCE.id) flowOf(HomeState(loading = false, sources = all))
            else combine(
                core.library.continueWatching(src.id),
                core.library.recentChannels(src.id, 15),
                core.catalog.movies(src.id, Categories.RECENT, 30),
                core.catalog.series(src.id, Categories.RECENT, 30),
            ) { cw, ch, mv, se ->
                val hero = (mv.filter { !it.poster.isNullOrBlank() }.take(4).map {
                    HeroItem(it.sourceId, it.id, it.name, it.poster, listOfNotNull(it.year?.toString(), Format.rating(it.rating)?.let { r -> "★ $r" }).joinToString("  ·  "), false)
                } + se.filter { !it.cover.isNullOrBlank() }.take(3).map {
                    HeroItem(it.sourceId, it.id, it.name, it.cover, listOfNotNull(it.year?.toString(), it.genre).joinToString("  ·  "), true)
                })
                HomeState(false, src, all, hero, cw, ch, mv, se)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val dismissed = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Expiry banners (neutral wording from the shared core), minus those dismissed this session. */
    val notices: StateFlow<List<ExpiryNotice>> = combine(core.reminders.notices, dismissed) { list, d ->
        list.filter { noticeKey(it) !in d }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismiss(n: ExpiryNotice) { dismissed.value = dismissed.value + noticeKey(n) }

    private fun noticeKey(n: ExpiryNotice) = "${n.sourceId}:${n.daysLeft}:${n.expired}"

    /** True while the catalog/EPG of the list is being refreshed (by this button or anything else). */
    val refreshing: StateFlow<Boolean> = core.sync.state.map { it is SyncState.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * "Aggiorna": re-downloads channels, movies, series and TV guide of the active list, in the
     * blocking progress dialog (also when an automatic sync is already running: the dialog
     * follows it, then the requested sync).
     */
    fun refresh() {
        val id = state.value.source?.id?.takeIf { it != SourceRepository.NO_SOURCE.id } ?: return
        app.userSync.start(id)
    }

    fun switchSource(id: Long) = viewModelScope.launch {
        core.sources.setActive(id)
        app.appScope.launch { runCatching { core.sync.syncIfStale(id) } }
    }
}

@Composable
fun HomeScreen(actions: AppActions, onSwitchTab: (Tab) -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val notices by vm.notices.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
    val posterWidth = if (wide) 150.dp else 118.dp
    val wideWidth = if (wide) 300.dp else 240.dp

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (state.isEmpty) {
            Column {
                HomeTopBar(state, actions, vm::switchSource, refreshing, vm::refresh, transparent = false)
                EmptyState(Icons.Rounded.PlaylistPlay, stringResource(R.string.empty_home)) {
                    PrimaryButton(stringResource(R.string.nav_settings), onClick = actions::openSettings)
                }
            }
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero", contentType = "hero") {
                Box {
                    if (state.hero.isNotEmpty()) HeroCarousel(state.hero, actions, wide)
                    else Spacer(Modifier.statusBarsPadding().height(64.dp))
                    HomeTopBar(state, actions, vm::switchSource, refreshing, vm::refresh, transparent = true)
                }
            }
            items(notices, key = { "notice_${it.sourceId}_${it.daysLeft}_${it.expired}" }, contentType = { "notice" }) { n ->
                ExpiryBanner(n, onDismiss = { vm.dismiss(n) })
            }
            if (state.continueWatching.isNotEmpty()) {
                item(key = "cw_h", contentType = "header") { SectionHeader(stringResource(R.string.row_continue)) }
                item(key = "cw", contentType = "row") {
                    val first = state.continueWatching.first()
                    LazyRow(
                        state = rememberRecencyRowState("${first.sourceId}:${first.type}:${first.itemId}"),
                        contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.continueWatching, key = { "${it.sourceId}:${it.type}:${it.itemId}" }, contentType = { "wide" }) { p ->
                            WideCard(
                                title = p.title, imageUrl = p.image, subtitle = p.subtitle,
                                progress = p.fraction, modifier = Modifier.width(wideWidth),
                                onClick = { actions.playProgress(p) },
                            )
                        }
                    }
                }
            }
            if (state.recentChannels.isNotEmpty()) {
                item(key = "ch_h", contentType = "header") { SectionHeader(stringResource(R.string.row_recent_channels)) }
                item(key = "ch", contentType = "row") {
                    LazyRow(
                        state = rememberRecencyRowState(state.recentChannels.first().itemId),
                        contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.recentChannels, key = { it.itemId }, contentType = { "channel" }) { p ->
                            WideCard(
                                title = p.title, imageUrl = p.image, live = true, fitImage = true,
                                modifier = Modifier.width(if (wide) 220.dp else 170.dp),
                                onClick = { actions.playProgress(p) },
                            )
                        }
                    }
                }
            }
            if (state.recentMovies.isNotEmpty()) {
                item(key = "mv_h", contentType = "header") { RowHeader(stringResource(R.string.row_recent_movies)) { onSwitchTab(Tab.MOVIES) } }
                item(key = "mv", contentType = "row") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.recentMovies, key = { it.id }, contentType = { "poster" }) { m ->
                            PosterCard(m.name, m.poster, subtitle = m.year?.toString(), rating = Format.rating(m.rating), width = posterWidth) { actions.openMovie(m) }
                        }
                    }
                }
            }
            if (state.recentSeries.isNotEmpty()) {
                item(key = "se_h", contentType = "header") { RowHeader(stringResource(R.string.row_recent_series)) { onSwitchTab(Tab.SERIES) } }
                item(key = "se", contentType = "row") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.recentSeries, key = { it.id }, contentType = { "poster" }) { s ->
                            PosterCard(s.name, s.cover, subtitle = s.year?.toString(), rating = Format.rating(s.rating), width = posterWidth) { actions.openSeries(s) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun RowHeader(title: String, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f))
        Text(
            "›",
            style = MaterialTheme.typography.headlineSmall,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(end = Dimens.Gutter).clip(CircleShape).clickable(onClick = onMore).padding(horizontal = 8.dp),
        )
    }
}

/**
 * State for a row ordered by recency ("Continua a guardare", "Canali recenti"): what was just watched
 * is inserted first, and a keyed LazyRow stays anchored on the previous first item, which hid the new
 * one off-screen to the left. Back to the start when the first item changes (unless scrolled away).
 */
@Composable
private fun rememberRecencyRowState(firstKey: String): LazyListState {
    val rowState = rememberLazyListState()
    LaunchedEffect(firstKey) { if (rowState.firstVisibleItemIndex <= 1) rowState.scrollToItem(0) }
    return rowState
}

@Composable
private fun HomeTopBar(
    state: HomeState,
    actions: AppActions,
    onSwitch: (Long) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    transparent: Boolean,
) {
    val isTv = LocalIsTv.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (transparent) Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))) else Modifier)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("StePlayer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.weight(1f))
        if (state.sources.size > 1) {
            Box {
                SecondaryButton(state.source?.name ?: "", onClick = { menu = true }, icon = Icons.Rounded.SwapHoriz)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.sources.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = { menu = false; onSwitch(s.id) })
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        if (state.source != null && state.source.id != SourceRepository.NO_SOURCE.id) {
            RefreshButton(refreshing, onRefresh)
            Spacer(Modifier.width(4.dp))
        }
        if (!isTv) {
            RoundIconButton(Icons.Rounded.Search, stringResource(R.string.nav_search), actions::openSearch)
            RoundIconButton(Icons.Rounded.Settings, stringResource(R.string.nav_settings), actions::openSettings)
        }
    }
}

@Composable
private fun HeroCarousel(items: List<HeroItem>, actions: AppActions, wide: Boolean) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(7_000)
            index = (index + 1) % items.size
        }
    }
    val item = items[index.coerceIn(0, items.lastIndex)]
    Box(Modifier.fillMaxWidth().height(if (wide) 420.dp else 480.dp)) {
        AnimatedContent(item, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }, label = "hero") { h ->
            ArtImage(h.image, h.title, Modifier.fillMaxSize(), placeholderTextVisible = false)
        }
        Box(Modifier.matchParentSize().background(Palette.ScrimBottom))
        if (wide) Box(Modifier.matchParentSize().background(Palette.ScrimLeft))
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = Dimens.Section, vertical = 20.dp)
                // Half the width on wide screens, but never so narrow that the two buttons wrap (7" tablets: ~250 dp).
                .then(if (wide) Modifier.widthIn(min = 360.dp).fillMaxWidth(0.5f) else Modifier.fillMaxWidth()),
        ) {
            Text(item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!item.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.isSeries) {
                    // A series has nothing to "play" from the hero: one button to its episodes.
                    PrimaryButton(
                        stringResource(R.string.episodes),
                        icon = Icons.Rounded.VideoLibrary,
                        onClick = { actions.openSeries(item.sourceId, item.id) },
                    )
                } else {
                    PrimaryButton(
                        stringResource(R.string.watch_now),
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { actions.playMovie(Movie(item.sourceId, item.id, item.title, item.image, null, null, null, null, null)) },
                    )
                    SecondaryButton(
                        stringResource(R.string.details), icon = Icons.Rounded.Info,
                        onClick = { actions.openMovie(item.sourceId, item.id) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier
                            .height(4.dp)
                            .width(if (i == index) 22.dp else 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == index) Palette.Accent else Color.White.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpiryBanner(n: ExpiryNotice, onDismiss: () -> Unit) {
    val color = if (n.expired) Palette.Live else Color(0xFFFFB020)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Icons.Rounded.Info, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(n.sourceName, style = MaterialTheme.typography.labelLarge, color = color)
            Text(n.message, style = MaterialTheme.typography.bodyMedium)
        }
        RoundIconButton(Icons.Rounded.Close, stringResource(R.string.dismiss), onDismiss, tint = Palette.TextSecondary)
    }
}

/**
 * "Aggiorna" — circular-arrows icon + text label, understandable by everyone.
 * The label never changes (the button keeps its width); only the icon spins while the list is
 * refreshing, and accessibility services hear "Aggiornamento in corso".
 */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    // Read in the graphics layer (draw phase): the spin doesn't recompose the button 60 times a second.
    val rotation: State<Float>? = if (refreshing) {
        val t = rememberInfiniteTransition(label = "refresh")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    } else null
    val shape = RoundedCornerShape(50)
    val runningLabel = stringResource(R.string.refresh_running)
    Row(
        Modifier
            .then(if (refreshing) Modifier.clearAndSetSemantics { contentDescription = runningLabel } else Modifier)
            .focusHighlight(shape, 1.06f)
            .clip(shape)
            .background(Palette.SurfaceElevated.copy(alpha = 0.85f))
            .clickable(enabled = !refreshing, onClickLabel = stringResource(R.string.refresh_a11y), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Refresh, null, tint = Palette.TextPrimary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation?.value ?: 0f })
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.refresh), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
