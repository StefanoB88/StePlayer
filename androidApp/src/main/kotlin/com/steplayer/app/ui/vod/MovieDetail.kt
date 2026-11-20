package com.steplayer.app.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.cast.CastButton
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.onboarding.errorMessage
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.app.util.Format
import com.steplayer.shared.data.DownloadRepository
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus
import com.steplayer.shared.model.MovieDetail
import com.steplayer.shared.model.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MovieDetailViewModel(private val app: StePlayerApp, private val sourceId: Long, private val id: String) : ViewModel() {
    private val core = app.core
    private val _detail = MutableStateFlow<MovieDetail?>(null)
    val detail = _detail.asStateFlow()
    private val _notFound = MutableStateFlow(false)
    val notFound = _notFound.asStateFlow()
    /**
     * Re-read whenever any watch progress is written, not only when the screen resumes: the player
     * saves the position asynchronously while it closes, which can land after this screen resumed
     * (it then kept showing "Riproduci" instead of "Riprendi da …").
     */
    val progress: StateFlow<WatchProgress?> = core.library.continueWatching(sourceId, 1)
        .map { core.library.progress(sourceId, ContentType.MOVIE, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favorite: StateFlow<Boolean> = core.library.isFavorite(sourceId, ContentType.MOVIE, id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val download: StateFlow<Download?> = core.downloads.observe(DownloadRepository.idFor(sourceId, ContentType.MOVIE.name, id))
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val movie = core.catalog.movie(sourceId, id)
            if (movie == null) { _notFound.value = true; return@launch }
            _detail.value = MovieDetail(movie, null, null, null, null, null, null, null, null)
            // Extra info comes from the network: keep the basic page if it fails (no crash).
            runCatching { core.catalog.movieDetail(movie) }.onSuccess { _detail.value = it }
        }
    }

    fun toggleFavorite() = viewModelScope.launch { core.library.toggleFavorite(sourceId, ContentType.MOVIE, id) }

    fun onDownloadClick(onError: (Throwable) -> Unit, onStarted: () -> Unit) = viewModelScope.launch {
        val d = download.value
        runCatching<Unit> {
            withContext(Dispatchers.Default) {
                when (d?.status) {
                    null -> { core.downloads.downloadMovie(_detail.value?.movie ?: return@withContext); withContext(Dispatchers.Main) { onStarted() } }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> core.downloads.resume(d.id)
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> core.downloads.pause(d.id)
                    DownloadStatus.COMPLETED -> Unit
                }
            }
        }.onFailure(onError)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(sourceId: Long, id: String, actions: AppActions) {
    val vm = appViewModel(key = "movie_${sourceId}_$id") { MovieDetailViewModel(it, sourceId, id) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
    val context = LocalContext.current


    val scroll = rememberScrollState()
    val heroHeight = if (wide) 380.dp else 300.dp
    val compact = rememberDetailCompact()
    val collapse = rememberDetailCollapse()
    val playFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        val d = detail
        when {
            notFound -> EmptyState(Icons.Rounded.Movie, stringResource(R.string.empty_movies))
            d == null -> LoadingBox()
            else -> {
                val m = d.movie
                val p = progress
                val canResume = p != null && p.positionMs > 30_000 && p.fraction < 0.95f
                // TV: start on the play button (and again when it turns into "Riprendi"), so it is on screen and OK plays.
                if (isTv) LaunchedEffect(canResume) { runCatching { playFocus.requestFocus() } }
                val header: @Composable () -> Unit = {
                    Text(m.name, style = MaterialTheme.typography.headlineMedium, modifier = collapse.titleModifier)
                    Spacer(Modifier.height(8.dp))
                    val meta = listOfNotNull(
                        m.year?.toString(),
                        Format.minutes(d.durationSec?.toLong()),
                        Format.rating(m.rating)?.let { "★ $it" },
                        m.extension?.uppercase(),
                    ).joinToString("   ·   ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                }
                val body: @Composable () -> Unit = {
                    if (canResume && p != null) {
                        ProgressBar(p.fraction, Modifier.fillMaxWidth(if (wide) 0.4f else 1f).padding(bottom = 12.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (canResume && p != null) {
                            PrimaryButton(
                                stringResource(R.string.resume_from, Format.duration(p.positionMs)), icon = Icons.Rounded.PlayArrow,
                                onClick = { actions.playMovie(m, p.positionMs) }, modifier = Modifier.focusRequester(playFocus),
                            )
                            SecondaryButton(stringResource(R.string.restart), icon = Icons.Rounded.Replay, onClick = { actions.playMovie(m, 0) })
                        } else {
                            PrimaryButton(
                                stringResource(R.string.play), icon = Icons.Rounded.PlayArrow,
                                onClick = { actions.playMovie(m, 0) }, modifier = Modifier.focusRequester(playFocus),
                            )
                        }
                        if (!isTv) {
                            SecondaryButton(
                                downloadLabel(download), icon = downloadIcon(download),
                                tint = if (download?.status == DownloadStatus.COMPLETED) Palette.Success else Palette.TextPrimary,
                                onClick = {
                                    vm.onDownloadClick(
                                        onError = { context.toast(errorMessage(context, it)) },
                                        onStarted = { context.toast(context.getString(R.string.download_started)) },
                                    )
                                },
                            )
                        }
                        SecondaryButton(
                            "", icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            tint = if (favorite) Palette.Live else Palette.TextPrimary,
                            onClick = vm::toggleFavorite,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    // TV: the text has no buttons below it, so it is a focus stop of its own (the page scrolls to it).
                    val hasText = listOf(d.plot, d.genre, d.director, d.cast).any { !it.isNullOrBlank() }
                    Column(if (hasText) Modifier.tvReadable() else Modifier) {
                        d.plot?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary.copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth(if (wide && !compact) 0.7f else 1f))
                            Spacer(Modifier.height(16.dp))
                        }
                        MetaLine(stringResource(R.string.genre_label), d.genre)
                        MetaLine(stringResource(R.string.director_label), d.director)
                        MetaLine(stringResource(R.string.cast_label), d.cast)
                    }
                }
                if (compact) {
                    DetailBackdropBackground(d.backdrop ?: m.poster, m.name)
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        CompactDetailHeader(m.poster, m.name) {
                            header()
                            Spacer(Modifier.height(16.dp))
                            body()
                        }
                        Spacer(Modifier.navigationBarsPadding().height(24.dp))
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        BackdropHeader(d.backdrop ?: m.poster, m.name, heroHeight)
                        Row(
                            Modifier.fillMaxWidth().offset(y = (-120).dp).padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            if (wide) {
                                ArtImage(m.poster, m.name, Modifier.width(180.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                                Spacer(Modifier.width(24.dp))
                            }
                            Column(Modifier.weight(1f)) { header() }
                        }
                        Column(Modifier.offset(y = (-100).dp).padding(horizontal = 24.dp)) {
                            body()
                            Spacer(Modifier.navigationBarsPadding())
                        }
                    }
                }
            }
        }
        DetailTopBar(detail?.movie?.name.orEmpty(), collapse.collapsed, onBack = actions::back) { scrim ->
            if (!isTv) ScrimmedIcon(scrim) { CastButton() }
        }
    }
}

@Composable
fun MetaLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$label  ", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 3)
    }
}
