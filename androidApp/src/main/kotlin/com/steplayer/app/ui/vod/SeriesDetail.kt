package com.steplayer.app.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.cast.CastButton
import com.steplayer.app.player.PlayerLauncher
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.ChipItem
import com.steplayer.app.ui.components.CategoryChips
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.onboarding.errorMessage
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.app.util.Format
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus
import com.steplayer.shared.model.Episode
import com.steplayer.shared.model.Series
import com.steplayer.shared.model.SeriesDetail
import com.steplayer.shared.model.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Immutable
data class SeriesUi(
    val series: Series? = null,
    val detail: SeriesDetail? = null,
    val loadingEpisodes: Boolean = true,
    val notFound: Boolean = false,
    val season: Int? = null,
    val progress: Map<String, WatchProgress> = emptyMap(),
    val downloads: Map<String, Download> = emptyMap(),
    val favorite: Boolean = false,
) {
    val seasons: List<Int> get() = detail?.seasons.orEmpty()
    val selectedSeason: Int? get() = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val episodes: List<Episode> get() = selectedSeason?.let { s -> detail?.episodesOf(s) }.orEmpty()

    /** The episode a "Play" button should start: resume the last watched, else the next, else the first. */
    val upNext: Pair<Episode, Long>? get() {
        val all = detail?.episodes.orEmpty()
        if (all.isEmpty()) return null
        val last = progress.values.maxByOrNull { it.updatedAt } ?: return all.first() to 0L
        val idx = all.indexOfFirst { it.id == last.itemId }
        if (idx < 0) return all.first() to 0L
        return if (last.fraction < 0.95f) all[idx] to last.positionMs
        else (all.getOrNull(idx + 1) ?: all[idx]) to 0L
    }
}

class SeriesDetailViewModel(private val app: StePlayerApp, private val sourceId: Long, private val id: String) : ViewModel() {
    private val core = app.core
    private val base = MutableStateFlow(SeriesUi())

    val state: StateFlow<SeriesUi> = combine(
        base,
        core.library.seriesProgress(sourceId, id),
        core.downloads.downloads.map { list -> list.filter { it.type == ContentType.EPISODE && it.parentId == id }.associateBy { it.itemId } },
        core.library.isFavorite(sourceId, ContentType.SERIES, id),
    ) { b, p, d, f -> b.copy(progress = p, downloads = d, favorite = f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesUi())

    init {
        viewModelScope.launch {
            val s = core.catalog.seriesById(sourceId, id)
            if (s == null) { base.value = base.value.copy(notFound = true, loadingEpisodes = false); return@launch }
            base.value = base.value.copy(series = s)
            val detail = runCatching { core.catalog.seriesDetail(s) }.getOrNull()
            base.value = base.value.copy(series = detail?.series ?: s, detail = detail, loadingEpisodes = false)
        }
    }

    fun selectSeason(season: Int) { base.value = base.value.copy(season = season) }

    fun toggleFavorite() = viewModelScope.launch { core.library.toggleFavorite(sourceId, ContentType.SERIES, id) }

    fun onDownload(ep: Episode, onError: (Throwable) -> Unit, onStarted: () -> Unit) = viewModelScope.launch {
        val series = state.value.series ?: return@launch
        val d = state.value.downloads[ep.id]
        runCatching<Unit> {
            withContext(Dispatchers.Default) {
                when (d?.status) {
                    null -> { core.downloads.downloadEpisode(series, ep); withContext(Dispatchers.Main) { onStarted() } }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> core.downloads.resume(d.id)
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> core.downloads.pause(d.id)
                    DownloadStatus.COMPLETED -> Unit
                }
            }
        }.onFailure(onError)
    }
}

@Composable
fun SeriesDetailScreen(sourceId: Long, id: String, actions: AppActions) {
    val vm = appViewModel(key = "series_${sourceId}_$id") { SeriesDetailViewModel(it, sourceId, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
    val context = LocalContext.current
    val play: (Episode, Long) -> Unit = { ep, start -> PlayerLauncher.episode(context, sourceId, id, ep.id, start) }

    val list = rememberLazyListState()
    val heroHeight = if (wide) 360.dp else 280.dp
    // The header item (with the title) may be disposed once scrolled far away: past it, always collapsed.
    val collapse = rememberDetailCollapse(forceCollapsed = { list.firstVisibleItemIndex > 0 })

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        val s = state.series
        when {
            state.notFound -> EmptyState(Icons.Rounded.VideoLibrary, stringResource(R.string.empty_series))
            s == null -> LoadingBox()
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "header", contentType = "header") {
                    Column {
                        BackdropHeader(state.detail?.backdrop ?: s.cover, s.name, heroHeight)
                        Row(Modifier.fillMaxWidth().offset(y = (-90).dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
                            if (wide) {
                                ArtImage(s.cover, s.name, Modifier.width(160.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                                Spacer(Modifier.width(24.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.headlineMedium, modifier = collapse.titleModifier)
                                val meta = listOfNotNull(s.year?.toString(), Format.rating(s.rating)?.let { "★ $it" }, s.genre).joinToString("   ·   ")
                                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                            }
                        }
                        Column(Modifier.offset(y = (-70).dp).padding(horizontal = 24.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val next = state.upNext
                                if (next != null) {
                                    val (ep, pos) = next
                                    val label = "S${ep.season} E${ep.number}"
                                    PrimaryButton(
                                        if (pos > 30_000) stringResource(R.string.resume_from, Format.duration(pos)) + "  ·  $label"
                                        else stringResource(R.string.play) + "  ·  $label",
                                        icon = Icons.Rounded.PlayArrow,
                                        onClick = { play(ep, pos) },
                                    )
                                }
                                SecondaryButton(
                                    "", icon = if (state.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    tint = if (state.favorite) Palette.Live else Palette.TextPrimary,
                                    onClick = vm::toggleFavorite,
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            s.plot?.let {
                                Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary.copy(alpha = 0.9f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(10.dp))
                            }
                            MetaLine(stringResource(R.string.cast_label), state.detail?.cast)
                            MetaLine(stringResource(R.string.director_label), state.detail?.director)
                        }
                    }
                }
                if (state.seasons.size > 1) {
                    item(key = "seasons", contentType = "chips") {
                        val labels = state.seasons.map { ChipItem(it.toString(), context.getString(R.string.season_n, it)) }
                        CategoryChips(labels, state.selectedSeason?.toString(), { vm.selectSeason(it.toInt()) }, Modifier.offset(y = (-50).dp))
                    }
                }
                when {
                    state.loadingEpisodes -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Palette.Accent)
                        }
                    }
                    state.episodes.isEmpty() -> item(key = "empty") {
                        Text(stringResource(R.string.no_episodes), color = Palette.TextSecondary, modifier = Modifier.padding(24.dp))
                    }
                    else -> items(state.episodes, key = { it.id }, contentType = { "episode" }) { ep ->
                        EpisodeRow(
                            ep = ep,
                            progress = state.progress[ep.id],
                            download = state.downloads[ep.id],
                            showDownload = !isTv,
                            modifier = Modifier.offset(y = (-40).dp),
                            onPlay = {
                                val p = state.progress[ep.id]
                                play(ep, if (p != null && p.fraction < 0.95f) p.positionMs else 0L)
                            },
                            onDownload = {
                                vm.onDownload(
                                    ep,
                                    onError = { context.toast(errorMessage(context, it)) },
                                    onStarted = { context.toast(context.getString(R.string.download_started)) },
                                )
                            },
                        )
                    }
                }
            }
        }
        DetailTopBar(state.series?.name.orEmpty(), collapse.collapsed, onBack = actions::back) { scrim ->
            if (!isTv) ScrimmedIcon(scrim) { CastButton() }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    progress: WatchProgress?,
    download: Download?,
    showDownload: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .focusHighlight(shape, 1.02f)
            .clip(shape)
            .background(Palette.Surface)
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            ArtImage(ep.image, ep.title, Modifier.fillMaxSize())
            if (progress != null && progress.fraction > 0f) {
                ProgressBar(progress.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp), height = 3.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.number}. ${ep.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Format.minutes(ep.durationSec?.toLong())?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
            ep.plot?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showDownload) {
            Box(contentAlignment = Alignment.Center) {
                if (download?.status == DownloadStatus.RUNNING) {
                    CircularProgressIndicator(progress = { download.fraction }, color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.width(40.dp))
                }
                RoundIconButton(
                    downloadIcon(download), downloadLabel(download), onDownload,
                    tint = when (download?.status) {
                        DownloadStatus.COMPLETED -> Palette.Success
                        DownloadStatus.FAILED -> Palette.Live
                        else -> Palette.TextPrimary
                    },
                )
            }
        }
    }
}
