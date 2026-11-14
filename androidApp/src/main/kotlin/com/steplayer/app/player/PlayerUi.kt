package com.steplayer.app.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalDensity
import com.steplayer.shared.playback.AcquireResult
import com.steplayer.shared.playback.SessionState
import com.steplayer.shared.playback.StopReason
import androidx.compose.ui.text.font.FontFamily
import com.steplayer.shared.playback.PlaybackError
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.steplayer.app.R
import com.steplayer.app.cast.CastButton
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.LiveBadge
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.components.rememberNow
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import kotlinx.coroutines.delay

private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))
private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))

private enum class Sheet { AUDIO, TEXT, ASPECT, SPEED, MENU, SLEEP, AUDIO_SYNC, SUB_SYNC }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    controller: PlayerController,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onDiagnose: () -> Unit = {},
    onStopped: (StopReason) -> Unit = {},
    /** Window bounds of the video area (Picture-in-Picture source rect hint). */
    onVideoBounds: (android.graphics.Rect) -> Unit = {},
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val interaction by controller.interaction.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // Spinner only for loads longer than ~300 ms: quick zaps/seeks don't flash it.
    val loading = rememberDelayedTrue(ui.buffering && !ui.blocking && ui.castDevice == null)
    // One clock for every EPG progress bar of the player (redraw only, no recomposition).
    val now = rememberNow()

    LaunchedEffect(ui.stoppedReason) { ui.stoppedReason?.let(onStopped) }

    // Auto-hide controls after 4 s of inactivity while playing.
    LaunchedEffect(ui.controlsVisible, ui.isPlaying, interaction, sheet) {
        if (ui.controlsVisible && ui.isPlaying && sheet == null) {
            delay(4_000)
            controller.hideControls()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Plain values, so the view is only touched when one of them really changes.
        val generation = ui.playerGeneration
        val subtitleStyle = ui.subtitleStyle
        val resizeMode = when (ui.aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = controller.exo
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    keepScreenOn = true
                }
            },
            update = { view ->
                if (generation >= 0 && view.player !== controller.exo) view.player = controller.exo
                if (view.getTag(R.id.subtitle_style_tag) != subtitleStyle) {
                    view.subtitleView?.applyStyle(subtitleStyle)
                    view.setTag(R.id.subtitle_style_tag, subtitleStyle)
                }
                if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { c ->
                    val r = c.boundsInWindow()
                    onVideoBounds(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
                }
                .then(if (ui.isLive && !isTv) Modifier.verticalSwipeToZap(controller) else Modifier)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (ui.drawerOpen) controller.setDrawer(false) else controller.toggleControls()
                },
        )

        if (ui.castDevice != null) CastingOverlay(ui)

        // Loading: only the spinner. When controls are visible the spinner takes the play button's
        // place instead (never a play button and a spinner at the same time).
        if (loading && !(ui.controlsVisible && !ui.inPip)) {
            CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }

        ui.retryIn?.let { secs -> RetryOverlay(secs, ui.retryError, onCancel = controller::cancelRetry) }
        ui.error?.let { ErrorOverlay(it.title, it.message, onRetry = controller::retry, onBack = onBack, onDiagnose = onDiagnose) }
        if (ui.statsVisible && !ui.inPip) StatsOverlay(ui.stats, Modifier.align(Alignment.TopStart))
        when (val st = ui.session) {
            is SessionState.AskStillWatching -> StillWatchingDialog(st.secondsLeft, onContinue = controller::userInteraction, onExit = onBack)
            is SessionState.SleepWarning -> SleepWarning(st.secondsLeft, controller, Modifier.align(Alignment.BottomStart))
            else -> Unit
        }
        ui.busy?.let { b -> BusyDialog(b, onPauseDownloads = controller::pauseDownloadsAndRetry, onCancel = onBack) }

        if (ui.pendingNumber.isNotEmpty()) NumberEntry(ui.pendingNumber, Modifier.align(Alignment.TopEnd))

        if (!ui.inPip) {
            AnimatedVisibility(ui.controlsVisible && !ui.blocking, enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
                Controls(ui, loading, now, controller, isTv, onBack, onPip, onSheet = { sheet = it; controller.userInteraction() })
            }
            AnimatedVisibility(
                ui.zapBanner && !ui.controlsVisible && !ui.drawerOpen,
                enter = fadeIn(tween(150)), exit = fadeOut(tween(250)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { ZapBanner(ui, now, onLastChannel = controller::lastChannel) }
            AnimatedVisibility(
                ui.drawerOpen,
                enter = slideInHorizontally(tween(220)) { -it }, exit = slideOutHorizontally(tween(200)) { -it },
            ) { ChannelDrawer(ui, now, controller) }
            ui.nextEpisode?.let { NextEpisodePrompt(ui, controller, Modifier.align(Alignment.BottomEnd)) }
        }
    }

    sheet?.let { s ->
        when (s) {
            Sheet.AUDIO -> OptionsDialog(
                stringResource(R.string.audio),
                ui.audioTracks.map { it.label to it.selected },
                onSelect = { i -> controller.selectTrack(C.TRACK_TYPE_AUDIO, ui.audioTracks[i]) },
                onDismiss = { sheet = null },
            )
            Sheet.TEXT -> OptionsDialog(
                stringResource(R.string.subtitles),
                listOf(stringResource(R.string.subtitles_off) to (!ui.textEnabled || ui.textTracks.none { it.selected })) +
                    ui.textTracks.map { it.label to (it.selected && ui.textEnabled) },
                onSelect = { i -> controller.selectTrack(C.TRACK_TYPE_TEXT, if (i == 0) null else ui.textTracks[i - 1]) },
                onDismiss = { sheet = null },
            )
            Sheet.ASPECT -> OptionsDialog(
                stringResource(R.string.aspect),
                listOf(R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_zoom).mapIndexed { i, r -> stringResource(r) to (ui.aspectMode == i) },
                onSelect = controller::setAspect,
                onDismiss = { sheet = null },
            )
            Sheet.MENU -> {
                val entries = buildList {
                    add(stringResource(R.string.sleep_timer) to Sheet.SLEEP)
                    add(stringResource(R.string.audio_sync) to Sheet.AUDIO_SYNC)
                    if (ui.textTracks.isNotEmpty()) add(stringResource(R.string.subtitle_sync) to Sheet.SUB_SYNC)
                    add(stringResource(R.string.stats) to null)
                    add(stringResource(R.string.why_stalls) to null)
                }
                val stats = stringResource(R.string.stats)
                MenuDialog(
                    stringResource(R.string.player_menu),
                    entries.map { (label, _) -> if (label == stats) "$label: ${if (ui.statsVisible) "ON" else "OFF"}" else label },
                    onSelect = { i ->
                        val (label, next) = entries[i]
                        when {
                            next != null -> sheet = next
                            label == stats -> { controller.toggleStats(); sheet = null }
                            else -> { sheet = null; onDiagnose() }
                        }
                    },
                    onDismiss = { sheet = null },
                )
            }
            Sheet.SLEEP -> {
                val values = buildList {
                    add(0); addAll(listOf(15, 30, 60, 90)); if (ui.isLive && ui.now != null) add(-1)
                }
                val off = stringResource(R.string.sleep_off)
                val eop = stringResource(R.string.sleep_end_of_program)
                val labels = values.map { v -> when (v) { 0 -> off; -1 -> eop; else -> stringResource(R.string.sleep_minutes, v) } }
                OptionsDialog(
                    ui.sleepEndsAt?.let { stringResource(R.string.sleep_active, Format.clock(it)) } ?: stringResource(R.string.sleep_timer),
                    labels.mapIndexed { i, l -> l to (values[i] == 0 && ui.sleepEndsAt == null) },
                    onSelect = { controller.setSleepTimer(values[it]) },
                    onDismiss = { sheet = null },
                )
            }
            Sheet.AUDIO_SYNC -> DelayDialog(
                stringResource(R.string.audio_sync), ui.audioDelayMs, step = 50, onChange = controller::setAudioDelay, onDismiss = { sheet = null },
            )
            Sheet.SUB_SYNC -> DelayDialog(
                stringResource(R.string.subtitle_sync), ui.subtitleDelayMs, step = 100, onChange = controller::setSubtitleDelay, onDismiss = { sheet = null },
            )
            Sheet.SPEED -> {
                val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                OptionsDialog(
                    stringResource(R.string.speed),
                    speeds.map { (if (it == 1f) "Normale" else "${it}x") to (ui.speed == it) },
                    onSelect = { controller.setSpeed(speeds[it]) },
                    onDismiss = { sheet = null },
                )
            }
        }
    }
}

@Composable
private fun Controls(
    ui: PlayerUiState,
    loading: Boolean,
    now: State<Long>,
    controller: PlayerController,
    isTv: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSheet: (Sheet) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(TopScrim).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (ui.isLive) ui.now?.title else ui.subtitle
                if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (ui.isLive && ui.hasLastChannel) {
                RoundIconButton(Icons.Rounded.History, stringResource(R.string.last_channel), controller::lastChannel, tint = Color.White)
            }
            if (ui.isLive && ui.channels.size > 1) {
                RoundIconButton(Icons.AutoMirrored.Rounded.List, stringResource(R.string.channel_list), { controller.setDrawer(true) }, tint = Color.White)
            }
            if (ui.audioTracks.size > 1) RoundIconButton(Icons.Rounded.Audiotrack, stringResource(R.string.audio), { onSheet(Sheet.AUDIO) }, tint = Color.White)
            if (ui.textTracks.isNotEmpty()) RoundIconButton(Icons.Rounded.ClosedCaption, stringResource(R.string.subtitles), { onSheet(Sheet.TEXT) }, tint = Color.White)
            RoundIconButton(Icons.Rounded.AspectRatio, stringResource(R.string.aspect), { onSheet(Sheet.ASPECT) }, tint = Color.White)
            RoundIconButton(Icons.Rounded.Tune, stringResource(R.string.player_menu), { onSheet(Sheet.MENU) }, tint = Color.White)
            if (!ui.isLive) RoundIconButton(Icons.Rounded.Speed, stringResource(R.string.speed), { onSheet(Sheet.SPEED) }, tint = Color.White)
            if (!isTv) {
                RoundIconButton(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.pip), onPip, tint = Color.White)
                CastButton()
            }
        }

        // Center transport
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.canSeek) RoundIconButton(Icons.Rounded.Replay10, stringResource(R.string.rewind_10), { controller.seekBy(-10_000); controller.showControls() }, size = 56.dp, tint = Color.White)
            // One focus target for both states: on TV the focus used to vanish when the spinner
            // was swapped for the play/pause button (or back) while the controls were open.
            Box(
                Modifier
                    .size(76.dp)
                    .focusRequester(playFocus)
                    .focusHighlight(CircleShape, 1.1f, enabled = !loading)
                    .clip(CircleShape)
                    .background(if (loading) Color.Transparent else Color.White.copy(alpha = 0.16f))
                    .clickable { if (!loading) controller.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                } else {
                    Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(76.dp * 0.55f))
                }
            }
            if (ui.canSeek) RoundIconButton(Icons.Rounded.Forward10, stringResource(R.string.forward_10), { controller.seekBy(10_000); controller.showControls() }, size = 56.dp, tint = Color.White)
        }

        // Bottom: seek bar (VOD) or EPG info (live)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BottomScrim).padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            if (ui.isLive) LiveInfo(ui, now) else SeekBar(ui, controller)
        }
    }
}

@Composable
private fun LiveInfo(ui: PlayerUiState, now: State<Long>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveBadge()
        Spacer(Modifier.width(10.dp))
        Text(ui.now?.title ?: stringResource(R.string.no_epg), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        ui.now?.let { Text("${Format.clock(it.startAt)} – ${Format.clock(it.endAt)}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f)) }
    }
    ui.now?.let {
        Spacer(Modifier.height(10.dp))
        ProgressBar({ it.progress(now.value) }, Modifier.fillMaxWidth(), height = 4.dp)
    }
    ui.next?.let {
        Spacer(Modifier.height(8.dp))
        Text("${stringResource(R.string.next)}: ${Format.clock(it.startAt)}  ${it.title}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
    }
}

@Composable
private fun SeekBar(ui: PlayerUiState, controller: PlayerController) {
    // Collected here, so the 2 Hz position updates recompose only the seek bar.
    val position by controller.position.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else position.positionMs.toFloat() / duration
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = { dragging = true; dragValue = it; controller.showControls() },
        onValueChangeFinished = {
            controller.seekTo((dragValue * duration).toLong())
            dragging = false
        },
        enabled = ui.canSeek,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Palette.Accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        ),
        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50), 1.0f),
    )
    Row {
        Text(Format.duration(if (dragging) (dragValue * duration).toLong() else position.positionMs), style = MaterialTheme.typography.labelLarge, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(
            if (ui.durationMs > 0) "-" + Format.duration(ui.durationMs - position.positionMs) else "",
            style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ZapBanner(ui: PlayerUiState, now: State<Long>, onLastChannel: () -> Unit) {
    Row(
        Modifier
            .padding(24.dp)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface.copy(alpha = 0.92f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(ui.artwork, ui.title, Modifier.size(width = 80.dp, height = 52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val num = ui.channels.getOrNull(ui.channelIndex)?.channel?.num
                if (num != null) Text("$num  ", style = MaterialTheme.typography.titleMedium, color = Palette.Accent2)
                Text(ui.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(ui.now?.title ?: stringResource(R.string.no_epg), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.now?.let {
                Spacer(Modifier.height(6.dp))
                ProgressBar({ it.progress(now.value) }, Modifier.fillMaxWidth(), height = 3.dp)
            }
            ui.next?.let {
                Spacer(Modifier.height(6.dp))
                Text("${stringResource(R.string.next)}: ${Format.clock(it.startAt)}  ${it.title}", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            LiveBadge()
            if (ui.hasLastChannel && !LocalIsTv.current) {
                Spacer(Modifier.height(6.dp))
                RoundIconButton(Icons.Rounded.History, stringResource(R.string.last_channel), onLastChannel, size = 36.dp)
            }
        }
    }
}

@Composable
private fun ChannelDrawer(ui: PlayerUiState, now: State<Long>, controller: PlayerController) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (ui.channelIndex - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(250); runCatching { currentFocus.requestFocus() } }
    Column(
        Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Brush.horizontalGradient(listOf(Palette.Background.copy(alpha = 0.97f), Palette.Background.copy(alpha = 0.88f))))
            .padding(vertical = 16.dp),
    ) {
        Text(stringResource(R.string.channel_list), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(ui.channels, key = { _, it -> it.channel.id }, contentType = { _, _ -> "channel" }) { index, item ->
                val selected = index == ui.channelIndex
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .then(if (selected) Modifier.focusRequester(currentFocus) else Modifier)
                        .focusHighlight(shape, 1.02f)
                        .clip(shape)
                        .background(if (selected) Palette.Accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { controller.playChannelFromList(item.channel) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtImage(item.channel.logo, item.channel.name, Modifier.size(width = 56.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(item.channel.num?.toString(), item.channel.name).joinToString("  "),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        item.now?.let {
                            Text(it.title, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            ProgressBar({ it.progress(now.value) }, Modifier.fillMaxWidth(), height = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextEpisodePrompt(ui: PlayerUiState, controller: PlayerController, modifier: Modifier) {
    val ep = ui.nextEpisode ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(ep.id) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .padding(28.dp)
            .width(340.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface.copy(alpha = 0.95f))
            .padding(16.dp),
    ) {
        Text(
            ui.nextCountdown?.let { stringResource(R.string.next_episode_in, it) } ?: stringResource(R.string.next_episode),
            style = MaterialTheme.typography.labelLarge, color = Palette.Accent2,
        )
        Spacer(Modifier.height(4.dp))
        Text("S${ep.season} E${ep.number} · ${ep.title}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.play_now), onClick = controller::playNextEpisode, icon = Icons.Rounded.SkipNext, modifier = Modifier.focusRequester(focus))
            SecondaryButton(stringResource(R.string.cancel), onClick = controller::cancelNextEpisode)
        }
    }
}

@Composable
private fun CastingOverlay(ui: PlayerUiState) {
    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
        ArtImage(ui.artwork, ui.title, Modifier.fillMaxSize(), placeholderTextVisible = false)
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.7f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Cast, null, tint = Palette.Accent2, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.casting_to, ui.castDevice ?: ""), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

@Composable
private fun ErrorOverlay(title: String, message: String, onRetry: () -> Unit, onBack: () -> Unit, onDiagnose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.WifiOff, null, tint = Palette.Live, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(message, color = Palette.TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.focusRequester(focus))
                SecondaryButton(stringResource(R.string.back), onClick = onBack)
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton(stringResource(R.string.why_stalls), onClick = onDiagnose)
        }
    }
}

@Composable
private fun OptionsDialog(title: String, options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                itemsIndexed(options) { i, (label, selected) ->
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusHighlight(shape, 1.01f)
                            .clip(shape)
                            .clickable { onSelect(i); onDismiss() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Palette.SurfaceElevated,
    )
}

/** Phone/tablet: swipe up = next channel, swipe down = previous. */
@Composable
private fun Modifier.verticalSwipeToZap(controller: PlayerController): Modifier {
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    return pointerInput(controller) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                when {
                    total < -threshold -> controller.zap(+1)
                    total > threshold -> controller.zap(-1)
                }
                total = 0f
            },
            onVerticalDrag = { change, dy -> change.consume(); total += dy },
        )
    }
}

/** On-screen remote number entry: "2 0 1 _". */
@Composable
private fun NumberEntry(digits: String, modifier: Modifier) {
    val shown = (digits.toList().joinToString(" ") + if (digits.length < 4) " _" else "").trim()
    Box(
        modifier.padding(28.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface.copy(alpha = 0.92f))
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(shown, style = MaterialTheme.typography.displaySmall, color = Palette.Accent2)
    }
}

@Composable
private fun RetryOverlay(seconds: Int, error: PlaybackError?, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            if (error != null) {
                Text(error.title, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(error.message, color = Palette.TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
            }
            Text(stringResource(R.string.player_retry_in, seconds), style = MaterialTheme.typography.labelLarge, color = Palette.Accent2)
            Spacer(Modifier.height(18.dp))
            SecondaryButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.focusRequester(focus))
        }
    }
}

@Composable
private fun BusyDialog(busy: AcquireResult.Denied, onPauseDownloads: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.player_busy_title)) },
        text = { Text(busy.message) },
        confirmButton = {
            if (busy.canPauseDownloads) {
                TextButton(onClick = onPauseDownloads) { Text(stringResource(R.string.player_busy_pause_downloads), color = Palette.Accent) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        containerColor = Palette.SurfaceElevated,
    )
}

@Composable
private fun MenuDialog(title: String, items: List<String>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                items.forEachIndexed { i, label ->
                    val shape = RoundedCornerShape(10.dp)
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().focusHighlight(shape, 1.01f).clip(shape).clickable { onSelect(i) }.padding(vertical = 12.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Palette.SurfaceElevated,
    )
}

/** −/+ stepper with live preview (D-pad friendly; no slider precision issues on TV). */
@Composable
private fun DelayDialog(title: String, valueMs: Long, step: Long, onChange: (Long) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Remove, null, { onChange(valueMs - step) }, size = 52.dp, background = Palette.Surface, modifier = Modifier.focusRequester(focus))
                Text(
                    String.format(java.util.Locale.ITALY, "%+.${if (step < 100) 2 else 1}f s", valueMs / 1000.0),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 24.dp).widthIn(min = 120.dp),
                    textAlign = TextAlign.Center,
                )
                RoundIconButton(Icons.Rounded.Add, null, { onChange(valueMs + step) }, size = 52.dp, background = Palette.Surface)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onChange(0) }) { Text(stringResource(R.string.reset)) } },
        containerColor = Palette.SurfaceElevated,
    )
}

@Composable
private fun StatsOverlay(stats: PlayerStats, modifier: Modifier) {
    val lines = listOf(
        "Risoluzione  ${stats.resolution} @ ${stats.fps} fps",
        "Video        ${stats.videoCodec}${stats.bitrateKbps?.let { "  ${it} kbps" } ?: ""}",
        "Audio        ${stats.audioCodec}",
        "Buffer       ${String.format(java.util.Locale.ROOT, "%.1f", stats.bufferSec)} s (${stats.bufferProfile})",
        "Rete         ${stats.networkKbps?.let { "$it kbps" } ?: "—"}",
        "Frame persi  ${stats.droppedFrames}",
        "Blocchi      ${stats.stalls}",
    )
    Column(
        modifier.padding(top = 84.dp, start = 16.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(10.dp),
    ) {
        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Color.White) }
    }
}

@Composable
private fun StillWatchingDialog(seconds: Int, onContinue: () -> Unit, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.still_watching_title)) },
        text = { Text(stringResource(R.string.still_watching_body, seconds)) },
        confirmButton = { PrimaryButton(stringResource(R.string.still_watching_continue), onClick = onContinue, modifier = Modifier.focusRequester(focus)) },
        dismissButton = { SecondaryButton(stringResource(R.string.exit), onClick = onExit) },
        containerColor = Palette.SurfaceElevated,
    )
}

@Composable
private fun SleepWarning(seconds: Int, controller: PlayerController, modifier: Modifier) {
    Row(
        modifier.padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface.copy(alpha = 0.95f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.sleep_warning, seconds), style = MaterialTheme.typography.titleSmall)
        SecondaryButton(stringResource(R.string.sleep_plus15), onClick = { controller.extendSleep(15) })
        SecondaryButton(stringResource(R.string.cancel), onClick = controller::cancelSleep)
    }
}

/** [value], but becoming true only after it stayed true for [delayMs] (false is immediate). */
@Composable
private fun rememberDelayedTrue(value: Boolean, delayMs: Long = 300): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value) { delay(delayMs); shown = true } else shown = false
    }
    return value && shown
}
