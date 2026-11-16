package com.steplayer.app.player

import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlaybackException
import android.util.Log
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.cast.CastSupport
import com.steplayer.app.ui.live.nowNextLive
import com.steplayer.app.ui.onboarding.errorMessage
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ChannelWithEpg
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.model.Episode
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.PlayableStream
import com.steplayer.shared.model.Series
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.playback.AcquireResult
import com.steplayer.shared.playback.ConnectionLease
import com.steplayer.shared.playback.PlaybackError
import com.steplayer.shared.playback.PlaybackErrorKind
import com.steplayer.shared.playback.PlaybackErrors
import com.steplayer.shared.playback.RetryPolicy
import com.steplayer.shared.playback.StallWatchdog
import com.steplayer.shared.zap.ChannelZapper
import com.steplayer.shared.playback.BufferProfile
import com.steplayer.shared.playback.SessionState
import com.steplayer.shared.playback.StopReason
import com.steplayer.shared.playback.TrackInfo
import com.steplayer.shared.playback.WatchSessionTimer
import com.steplayer.shared.settings.PlayerPreferences
import com.steplayer.shared.settings.PlayerSettings
import com.steplayer.shared.settings.SubtitleStyle
import com.steplayer.shared.playback.Tracks as TrackPicker
import androidx.media3.exoplayer.analytics.AnalyticsListener
import android.os.LocaleList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** Playback position, polled twice a second while visible (kept apart from [PlayerUiState]). */
@Immutable
data class PlaybackPosition(val positionMs: Long = 0, val bufferedMs: Long = 0)

@Immutable
data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

@Immutable
data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val artwork: String? = null,
    val isLive: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    /** Short message over the video for a few seconds (e.g. an audio track the device cannot decode). */
    val notice: String? = null,
    /** Position/buffer live in [PlayerController.position] so the 2 Hz updates don't recompose the whole screen. */
    val durationMs: Long = 0,
    /** Final error (retries exhausted or not retryable): shown with a "Riprova" button. */
    val error: PlaybackError? = null,
    /** Seconds until the next automatic retry ("Riprovo tra Ns…"). */
    val retryIn: Int? = null,
    val retryError: PlaybackError? = null,
    /** The connection limit is used up (by downloads): ask the user what to do. */
    val busy: AcquireResult.Denied? = null,
    val now: EpgProgram? = null,
    val next: EpgProgram? = null,
    val channels: List<ChannelWithEpg> = emptyList(),
    val channelIndex: Int = -1,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val textEnabled: Boolean = true,
    val aspectMode: Int = 0,
    val speed: Float = 1f,
    val nextEpisode: Episode? = null,
    val nextCountdown: Int? = null,
    val castDevice: String? = null,
    /** Hidden at start: while a channel opens the user sees only the loading indicator. */
    val controlsVisible: Boolean = false,
    val drawerOpen: Boolean = false,
    val zapBanner: Boolean = false,
    val inPip: Boolean = false,
    /** Digits typed on the remote ("2 0 1 _"), empty when not typing. */
    val pendingNumber: String = "",
    val hasLastChannel: Boolean = false,
    /** "Stai ancora guardando?" / sleep warning state; null = nothing to show. */
    val session: SessionState? = null,
    val stoppedReason: StopReason? = null,
    val sleepEndsAt: Long? = null,
    val statsVisible: Boolean = false,
    val stats: PlayerStats = PlayerStats(),
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Bumped when the ExoPlayer instance is rebuilt (new buffer profile): views must re-bind. */
    val playerGeneration: Int = 0,
) {
    val canSeek get() = !isLive && durationMs > 0
    val blocking get() = error != null || retryIn != null || busy != null
}

/**
 * Owns the ExoPlayer (and the CastPlayer while casting).
 *
 * Connection hygiene (many streaming accounts allow ONE connection): every start, zap and retry goes
 * stop → clearMediaItems → release lease → acquire lease → prepare. Any error stops the player and
 * releases the lease BEFORE a retry is scheduled, and there is never more than one pending retry.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val context: Context,
    private val app: StePlayerApp,
    private val scope: CoroutineScope,
    /** Another player took over our connection: the host should close. */
    private val onRevokedExternally: () -> Unit,
    /** Video frame rate + size became known (auto frame rate "Sempre" on Android TV). */
    private val onVideoFormat: (frameRate: Float, width: Int, height: Int) -> Unit = { _, _, _ -> },
    /** The ExoPlayer instance changed (buffer profile switch): re-bind MediaSession etc. */
    private val onPlayerRebuilt: (ExoPlayer) -> Unit = {},
) {
    private val core = app.core
    private val main = Handler(Looper.getMainLooper())
    private val _ui = MutableStateFlow(PlayerUiState(aspectMode = app.prefs.aspectMode))
    val ui = _ui.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    val position = _position.asStateFlow()

    private val _interaction = MutableStateFlow(0L)
    /** Bumped on every user interaction; the UI restarts its auto-hide timer from it. */
    val interaction = _interaction.asStateFlow()

    private val offsets = SyncOffsets()
    private var settings = PlayerSettings()
    private var currentProfile: BufferProfile = app.bufferAdvisor(settings.bufferMode).profile()
    var exo: ExoPlayer = buildExo(currentProfile)
        private set
    private var sessionTimer = WatchSessionTimer(settings.stillWatchingAfterHours)
    private var contentKey: String? = null
    private var manualAudio = false
    private var manualText = false
    private var autoTracksApplied = false
    // Stats / stall bookkeeping
    private var droppedFrames = 0
    private var stallCount = 0
    private var networkBps: Long? = null
    private var hadBeenReady = false
    private var bufferingSince = 0L
    private var stallReported = false
    private var castPlayer: CastPlayer? = null
    private val casting get() = _ui.value.castDevice != null
    private val player: Player get() = castPlayer?.takeIf { casting } ?: exo

    private var request: PlayRequest? = null
    private var stream: PlayableStream? = null
    private var lease: ConnectionLease? = null
    private var leaseToken: Any? = null
    private var released = false
    private var stoppedInBackground = false
    private var resumePositionMs = 0L
    private var forcedHls = false
    /** Audio formats whose decoder failed on this device: hidden from the list, another track is used instead. */
    private val failedAudio = mutableSetOf<String>()
    private var audioRecoveries = 0

    private var retryPolicy: RetryPolicy = core.playback.retryPolicy(false)
    private var watchdog: StallWatchdog = core.playback.stallWatchdog(false)
    private var healthySince = 0L

    private var openJob: Job? = null
    private var retryJob: Job? = null
    /** Restart of the current stream (after an error, cast hand-off, back from background…). */
    private var startJob: Job? = null
    /** Bumped by every stop: an in-flight [startStream] that resumes after a stop/zap gives up. */
    private var startGeneration = 0
    /** Activity started (visible); false between onStop and onStart. */
    private var foreground = true
    private var zapJob: Job? = null
    private var epgJob: Job? = null
    private var countdownJob: Job? = null
    private var bannerJob: Job? = null
    private var pendingZapIndex: Int? = null
    private var numberJob: Job? = null
    private var zapper: ChannelZapper? = null

    // Current item details (progress saving, next episode)
    private var channel: Channel? = null
    private var movie: Movie? = null
    private var episode: Episode? = null
    private var series: Series? = null
    private var allEpisodes: List<Episode> = emptyList()

    private fun buildExo(profile: BufferProfile): ExoPlayer {
        val renderers = SyncRenderersFactory(context, offsets)
            // FFmpeg audio (decoder-ffmpeg): first on phones and tablets (uniform AC-3/E-AC-3/DTS/TrueHD decoding,
            // also where the chip claims a codec and then fails); after the system decoders on TV boxes, so Dolby/DTS
            // passthrough to amplifiers and soundbars keeps working.
            .setExtensionRendererMode(
                if (app.isTv) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            )
            .setEnableDecoderFallback(true)
        if (app.prefs.softwareDecoder.value) {
            renderers.setMediaCodecSelector(
                MediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { it.hardwareAccelerated }
                },
            )
        }
        // Buffer profile from the shared BufferAdvisor (Normale / Grande, auto-escalated on stalls).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, profile.bufferForPlaybackMs, profile.bufferForPlaybackAfterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // "Adatta frequenza schermo": 0 off, 1 only if seamless (default), 2 always (handled by the Activity).
                setVideoChangeFrameRateStrategy(
                    if (app.isTv && app.prefs.frameRateMode == 1) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
                )
            }
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlayerController.droppedFrames += droppedFrames
        }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            networkBps = bitrateEstimate
        }
    }

    /** New buffer profile → new LoadControl → new ExoPlayer (only between streams, never mid-stream). */
    private fun ensureProfile() {
        val profile = app.bufferAdvisor(settings.bufferMode).profile()
        if (profile == currentProfile) return
        currentProfile = profile
        exo.removeListener(listener)
        exo.removeAnalyticsListener(analytics)
        exo.release()
        exo = buildExo(profile)
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        onPlayerRebuilt(exo)
        _ui.update { it.copy(playerGeneration = it.playerGeneration + 1) }
    }

    // ------------------------------------------------------------------ open

    fun open(req: PlayRequest) {
        saveProgressNow()
        cancelPending()
        stopLocal()
        releaseLease()
        request = req
        stream = null
        manualAudio = false
        manualText = false
        contentKey = null
        audioRecoveries = 0
        if (failedAudio.isNotEmpty()) {
            failedAudio.clear()
            // Audio may have been switched off for the previous content.
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        }
        retryPolicy = core.playback.retryPolicy(req.kind == PlayKind.LIVE)
        _position.value = PlaybackPosition()
        _ui.update {
            it.copy(
                error = null, retryIn = null, busy = null, nextEpisode = null, nextCountdown = null,
                isLive = req.kind == PlayKind.LIVE, buffering = true, durationMs = 0,
            )
        }
        openJob = scope.launch {
            try {
                when (req.kind) {
                    PlayKind.LIVE -> openLive(req)
                    PlayKind.MOVIE -> openMovie(req)
                    PlayKind.EPISODE -> openEpisode(req)
                    PlayKind.CATCHUP -> openCatchup(req)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                showFinalError(PlaybackError(PlaybackErrorKind.UNKNOWN, context.getString(R.string.player_error), errorMessage(context, e), true))
            }
        }
    }

    private suspend fun openLive(req: PlayRequest) {
        val ch = core.catalog.channel(req.sourceId, req.itemId) ?: error(context.getString(R.string.empty_channels))
        val categoryId = req.categoryId ?: ch.categoryId
        if (_ui.value.channels.none { it.channel.id == ch.id }) loadChannelList(req.sourceId, categoryId, ch)
        playChannel(ch)
    }

    private fun loadChannelList(sourceId: Long, categoryId: String?, current: Channel) {
        epgJob?.cancel()
        epgJob = scope.launch {
            val list = if (categoryId != null) core.catalog.channels(sourceId, categoryId).first() else listOf(current)
            val channels = list.ifEmpty { listOf(current) }
            zapper?.updateList(channels) ?: run { zapper = ChannelZapper(channels, channel ?: current) }
            // Re-queried when a programme starts/ends (the old nested collect never re-ticked, so the
            // overlay kept showing the programme that was on when the player opened).
            core.nowNextLive(sourceId, channels).flowOn(Dispatchers.Default).collect { withEpg ->
                _ui.update { s ->
                    val shownId = pendingZapIndex?.let { s.channels.getOrNull(it)?.channel?.id } ?: channel?.id
                    val idx = withEpg.indexOfFirst { it.channel.id == shownId }
                    val cur = withEpg.getOrNull(idx)
                    s.copy(channels = withEpg, channelIndex = idx, now = cur?.now ?: s.now, next = cur?.next ?: s.next)
                }
            }
        }
    }

    private fun showChannelInfo(ch: Channel) {
        val idx = _ui.value.channels.indexOfFirst { it.channel.id == ch.id }
        val epg = _ui.value.channels.getOrNull(idx)
        _ui.update { it.copy(title = ch.name, subtitle = null, artwork = ch.logo, isLive = true, channelIndex = idx, now = epg?.now, next = epg?.next) }
    }

    private suspend fun playChannel(ch: Channel) {
        channel = ch; movie = null; episode = null
        zapper?.select(ch)
        _ui.update { it.copy(hasLastChannel = zapper?.lastChannel != null) }
        showChannelInfo(ch)
        retryPolicy = core.playback.retryPolicy(true)
        val s = withContext(Dispatchers.Default) { core.streams.live(ch) }
        startStream(s, 0)
        core.library.saveProgress(ch.sourceId, ContentType.LIVE, ch.id, null, ch.name, null, ch.logo, 0, 0)
    }

    private suspend fun openMovie(req: PlayRequest) {
        val m = core.catalog.movie(req.sourceId, req.itemId)
            ?: Movie(req.sourceId, req.itemId, req.title ?: "", null, null, null, null, req.extension, null)
        movie = m; channel = null; episode = null
        _ui.update { it.copy(title = m.name, subtitle = m.year?.toString(), artwork = m.poster, isLive = false) }
        startStream(withContext(Dispatchers.Default) { core.streams.movie(m) }, startPosition(req, ContentType.MOVIE, m.id))
    }

    private suspend fun openEpisode(req: PlayRequest) {
        val seriesId = req.seriesId.orEmpty()
        val ser = core.catalog.seriesById(req.sourceId, seriesId)
        val ep = core.catalog.episode(req.sourceId, req.itemId)
            ?: Episode(req.sourceId, seriesId, req.itemId, 0, 0, req.subtitle ?: req.title ?: "", null, null, null, req.extension)
        series = ser; episode = ep; movie = null; channel = null
        _ui.update { it.copy(title = ser?.name ?: req.title ?: ep.title, subtitle = episodeLabel(ep), artwork = ep.image ?: ser?.cover, isLive = false) }
        startStream(withContext(Dispatchers.Default) { core.streams.episode(ep) }, startPosition(req, ContentType.EPISODE, ep.id))
        if (ser != null) scope.launch {
            allEpisodes = runCatching { core.catalog.seriesDetail(ser).episodes }.getOrDefault(emptyList())
        }
    }

    private suspend fun openCatchup(req: PlayRequest) {
        val ch = core.catalog.channel(req.sourceId, req.itemId) ?: error(context.getString(R.string.empty_channels))
        channel = null; movie = null; episode = null
        val program = EpgProgram(ch.epgId.orEmpty(), req.catchupStart, req.catchupEnd, req.title ?: ch.name, null)
        _ui.update { it.copy(title = req.title ?: ch.name, subtitle = "${context.getString(R.string.catchup)} · ${ch.name}", artwork = ch.logo, isLive = false) }
        startStream(withContext(Dispatchers.Default) { core.streams.catchup(ch, program) }, 0)
    }

    private suspend fun startPosition(req: PlayRequest, type: ContentType, id: String): Long {
        if (req.startMs >= 0) return req.startMs
        val p = core.library.progress(req.sourceId, type, id) ?: return 0
        return if (p.positionMs > 30_000 && p.fraction < 0.95f) p.positionMs else 0
    }

    private fun episodeLabel(ep: Episode) =
        if (ep.season > 0) "S${ep.season} E${ep.number} · ${ep.title}" else ep.title

    // ------------------------------------------------------------------ connection-safe start

    private fun PlayableStream.isLocal() = url.startsWith("file://")

    /** The ONLY place a stream is opened. Order: stop → clear → release → acquire → prepare. */
    private suspend fun startStream(s: PlayableStream, startMs: Long) {
        if (released) return
        // A retry runs inside retryJob: cancelling it here used to cancel this very start.
        val self = currentCoroutineContext()[Job]
        if (retryJob !== self) retryJob?.cancel()
        if (startJob !== self) startJob?.cancel()
        stopLocal()
        releaseLease()
        val generation = startGeneration
        stream = s
        resumePositionMs = startMs
        _ui.update { it.copy(buffering = true, error = null, retryIn = null, busy = null) }

        if (!s.isLocal()) {
            if (!acquireLease(if (casting) "Chromecast" else _ui.value.title)) return
        }
        watchdog = core.playback.stallWatchdog(s.isLive)
        healthySince = 0
        hadBeenReady = false
        stallReported = false
        autoTracksApplied = false
        loadDelays()
        // Stopped/zapped/backgrounded while we were suspended: the newer action owns the player.
        if (generation != startGeneration || released) return
        if (casting) loadOnCast(startMs) else { ensureProfile(); prepareLocal(s, startMs) }
    }

    /** The only way to (re)start [s] outside open/zap: one tracked job, cancelled by any newer action. */
    private fun launchStart(s: PlayableStream, startMs: Long) {
        startJob?.cancel()
        startJob = scope.launch { startStream(s, startMs) }
    }

    /** @return true if granted. On denial the UI shows the "Connessione occupata" dialog. */
    private suspend fun acquireLease(label: String): Boolean {
        val req = request ?: return false
        val token = Any()
        leaseToken = token
        return when (val r = core.playback.connections.acquirePlayback(req.sourceId, label) { onLeaseRevoked(token) }) {
            is AcquireResult.Granted -> {
                if (released || leaseToken !== token) { r.lease.release(); false }
                else { lease = r.lease; true }
            }
            is AcquireResult.Denied -> {
                _ui.update { it.copy(busy = r, buffering = false) }
                false
            }
        }
    }

    /** Another player in the app took the connection: stop NOW (synchronously when on main). */
    private fun onLeaseRevoked(token: Any) {
        val action = {
            if (leaseToken === token) {
                cancelPending()
                stopLocal()
                lease = null
                leaseToken = null
                _ui.update { it.copy(buffering = false) }
                onRevokedExternally()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun releaseLease() {
        lease?.release()
        lease = null
        leaseToken = null
    }

    private fun stopLocal() {
        if (released) return
        startGeneration++
        exo.stop()
        exo.clearMediaItems()
    }

    private fun cancelPending() {
        openJob?.cancel()
        retryJob?.cancel()
        startJob?.cancel()
        zapJob?.cancel()
        countdownJob?.cancel()
        numberJob?.cancel()
        retryJob = null
        startJob = null
        pendingZapIndex = null
    }

    /** "Sospendi i download e guarda": pause downloads, give workers a moment to let go, retry. */
    fun pauseDownloadsAndRetry() {
        val s = stream ?: return
        _ui.update { it.copy(busy = null, buffering = true) }
        startJob?.cancel()
        startJob = scope.launch {
            runCatching { withContext(Dispatchers.Default) { core.downloads.pauseAll() } }
            repeat(8) {
                delay(500)
                startStream(s, resumePositionMs)
                if (_ui.value.busy == null) return@launch
            }
        }
    }

    private fun prepareLocal(s: PlayableStream, startMs: Long) {
        exo.setMediaSource(mediaSource(s, _ui.value.title, _ui.value.artwork), startMs.coerceAtLeast(0))
        exo.playbackParameters = exo.playbackParameters.withSpeed(if (s.isLive) 1f else _ui.value.speed)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun mimeFor(s: PlayableStream): String? = when {
        forcedHls -> MimeTypes.APPLICATION_M3U8
        s.container == "m3u8" || s.url.substringBefore('?').endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        s.container == "mpd" -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private suspend fun loadDelays() {
        val key = currentContentKey() ?: return
        if (key == contentKey) return
        contentKey = key
        val a = core.playerPreferences.audioDelayMs(key)
        val t = core.playerPreferences.subtitleDelayMs(key)
        offsets.audioDelayUs = a * 1000
        offsets.subtitleDelayUs = t * 1000
        _ui.update { it.copy(audioDelayMs = a, subtitleDelayMs = t) }
    }

    private fun currentContentKey(): String? {
        val req = request ?: return null
        return when {
            channel != null -> PlayerPreferences.contentKey(req.sourceId, "LIVE", channel!!.id)
            movie != null -> PlayerPreferences.contentKey(req.sourceId, "MOVIE", movie!!.id)
            episode != null -> PlayerPreferences.contentKey(req.sourceId, "EPISODE", episode!!.id)
            else -> null
        }
    }

    /** Live preview while the user drags; persisted per channel/content. */
    fun setAudioDelay(ms: Long) {
        val v = ms.coerceIn(-3_000, 3_000)
        offsets.audioDelayUs = v * 1000
        _ui.update { it.copy(audioDelayMs = v) }
        val key = contentKey ?: return
        app.appScope.launch { core.playerPreferences.setAudioDelayMs(key, v) }
        userInteraction()
    }

    /** Subtitle style changed from the player: saved as the default and applied live (settings flow → ui). */
    fun updateSubtitleStyle(change: (SubtitleStyle) -> SubtitleStyle) {
        app.appScope.launch { core.playerPreferences.update { it.copy(subtitleStyle = change(it.subtitleStyle)) } }
        userInteraction()
    }

    fun setSubtitleDelay(ms: Long) {
        val v = ms.coerceIn(-10_000, 10_000)
        offsets.subtitleDelayUs = v * 1000
        _ui.update { it.copy(subtitleDelayMs = v) }
        val key = contentKey ?: return
        app.appScope.launch { core.playerPreferences.setSubtitleDelayMs(key, v) }
        userInteraction()
    }

    private fun mediaSource(s: PlayableStream, title: String, artwork: String?): MediaSource {
        val ua = s.headers["User-Agent"] ?: app.userAgent
        val http = OkHttpDataSource.Factory(app.playbackHttp)
            .setUserAgent(ua)
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", true) })
        val dataSource = DefaultDataSource.Factory(context, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val item = MediaItem.Builder()
            .setUri(s.url)
            .setMimeType(mimeFor(s))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtworkUri(artwork?.let(Uri::parse)).build())
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(currentProfile.liveTargetOffsetMs.toLong()).build())
            .build()
        return DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(item)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _ui.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
            if (state == Player.STATE_READY) {
                hadBeenReady = true
                bufferingSince = 0
                stallReported = false
                _ui.update { it.copy(error = null, retryIn = null, durationMs = exo.duration.coerceAtLeast(0)) }
            }
            if (state == Player.STATE_BUFFERING && hadBeenReady) bufferingSince = System.currentTimeMillis()
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onTracksChanged(tracks: Tracks) = updateTracks(tracks)

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val f = exo.videoFormat ?: return
            if (f.frameRate > 0 && videoSize.width > 0) onVideoFormat(f.frameRate, videoSize.width, videoSize.height)
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = stream ?: return
            val position = if (s.isLive) 0L else exo.currentPosition.coerceAtLeast(resumePositionMs)
            // Rule: close the connection immediately, before deciding anything.
            stopLocal()
            releaseLease()
            resumePositionMs = position
            // Some M3U entries are HLS without a .m3u8 extension: retry once forcing HLS.
            if (!forcedHls && mimeFor(s) == null && hasCause<UnrecognizedInputFormatException>(error)) {
                forcedHls = true
                launchStart(s, position)
                return
            }
            if (hasCause<BehindLiveWindowException>(error)) {
                launchStart(s, 0)
                return
            }
            // An audio track the device cannot decode (common in MKV files): play another one, or none,
            // instead of stopping the whole film.
            val failed = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
            if (failed != null && MimeTypes.isAudio(failed.sampleMimeType) && audioRecoveries < 3) {
                audioRecoveries++
                failedAudio += audioKey(failed)
                _ui.update { it.copy(notice = "Audio ${MediaNames.codec(failed)} non supportato da questo dispositivo: uso un'altra traccia audio.") }
                launchStart(s, position)
                return
            }
            scope.launch { handleFailure(mapError(error, s)) }
        }
    }

    private inline fun <reified T : Throwable> findCause(e: Throwable): T? {
        var c: Throwable? = e
        while (c != null) { if (c is T) return c; c = c.cause }
        return null
    }

    private inline fun <reified T : Throwable> hasCause(e: Throwable) = findCause<T>(e) != null

    private suspend fun mapError(e: PlaybackException, s: PlayableStream): PlaybackError {
        // Technical codes go to the log only: users see a readable message.
        Log.w("StePlayer", "playback error ${e.errorCodeName}", e)
        val sourceId = request?.sourceId ?: return PlaybackErrors.unknown()
        val xtream = core.sources.byId(sourceId)?.type == SourceType.XTREAM
        suspend fun diagnosed(fallback: PlaybackError) =
            if (xtream && !s.isLocal()) core.playback.diagnose(sourceId) ?: fallback else fallback

        findCause<HttpDataSource.InvalidResponseCodeException>(e)?.let { http ->
            val base = core.playback.errorForHttpStatus(sourceId, http.responseCode)
            return if (http.responseCode == 403) diagnosed(base) else base
        }
        if (hasCause<SocketTimeoutException>(e) || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return PlaybackErrors.timeout()
        }
        if (hasCause<UnknownHostException>(e) || hasCause<ConnectException>(e) || hasCause<NoRouteToHostException>(e) ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ) {
            return PlaybackErrors.network()
        }
        val unsupported = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        if (e.errorCode in unsupported || hasCause<UnrecognizedInputFormatException>(e)) {
            // Say what the device cannot decode, when the failing renderer told us.
            val f = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
            return if (f != null) PlaybackErrors.unsupportedMedia(s.container, MediaNames.describe(f)) else PlaybackErrors.unsupported(s.container)
        }
        // Ambiguous (immediate EOF, unspecified IO…): ask the server what is going on.
        return diagnosed(PlaybackErrors.unknown())
    }

    /** Schedules at most one retry with a visible countdown, or shows the final error. */
    private fun handleFailure(err: PlaybackError) {
        retryJob?.cancel()
        val wait = retryPolicy.nextDelayMs(err)
        val s = stream
        if (wait == null || s == null) { showFinalError(err); return }
        retryJob = scope.launch {
            var left = ((wait + 999) / 1000).toInt()
            while (left > 0) {
                _ui.update { it.copy(retryIn = left, retryError = err, buffering = false) }
                delay(1_000)
                left--
            }
            _ui.update { it.copy(retryIn = null) }
            startStream(s, resumePositionMs)
        }
    }

    private fun showFinalError(err: PlaybackError) {
        retryJob?.cancel()
        stopLocal()
        releaseLease()
        _ui.update { it.copy(error = err, retryIn = null, buffering = false) }
    }

    /** "Annulla" on the retry countdown. */
    fun cancelRetry() {
        val err = _ui.value.retryError ?: PlaybackErrors.unknown()
        showFinalError(err)
    }

    /** "Riprova" (user action): fresh retry budget. */
    fun retry() {
        val req = request ?: return
        val s = stream
        retryPolicy.reset()
        _ui.update { it.copy(error = null) }
        if (s == null) open(req) else launchStart(s, resumePositionMs)
    }

    private fun onEnded() {
        saveProgressNow()
        // The server closed the stream: give the connection back.
        releaseLease()
        val next = nextEpisode() ?: return
        _ui.update { it.copy(nextEpisode = next) }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (i in 8 downTo 1) {
                _ui.update { it.copy(nextCountdown = i) }
                delay(1_000)
            }
            playNextEpisode()
        }
    }

    private fun nextEpisode(): Episode? {
        val ep = episode ?: return null
        val idx = allEpisodes.indexOfFirst { it.id == ep.id }
        return if (idx >= 0) allEpisodes.getOrNull(idx + 1) else null
    }

    fun playNextEpisode() {
        countdownJob?.cancel()
        val next = _ui.value.nextEpisode ?: nextEpisode() ?: return
        val req = request ?: return
        open(req.copy(itemId = next.id, startMs = 0, title = null, subtitle = null, extension = null))
    }

    fun cancelNextEpisode() {
        countdownJob?.cancel()
        _ui.update { it.copy(nextEpisode = null, nextCountdown = null) }
    }

    // ------------------------------------------------------------------ controls

    fun togglePlay() {
        val p = player
        if (p.playbackState == Player.STATE_ENDED || (p.playbackState == Player.STATE_IDLE && !casting)) {
            stream?.let { s -> launchStart(s, 0) }
            showControls()
            return
        }
        if (p.isPlaying) p.pause() else p.play()
        showControls()
    }

    fun seekBy(deltaMs: Long) {
        if (!_ui.value.canSeek) return
        val p = player
        // Duration can be unknown for a moment (e.g. right after a Cast hand-off): never clamp to 0.
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: _ui.value.durationMs
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
        _position.update { it.copy(positionMs = p.currentPosition) }
    }

    fun seekTo(ms: Long) {
        if (!_ui.value.canSeek) return
        val target = ms.coerceIn(0, _ui.value.durationMs)
        player.seekTo(target)
        _position.update { it.copy(positionMs = target) }
    }

    /** +1 = next channel in the current category, -1 = previous (wraps). */
    fun zap(delta: Int) {
        userInteraction()
        val z = zapper ?: return
        if (!_ui.value.isLive) return
        scheduleZap(if (delta > 0) z.next() else z.previous())
    }

    /** "↺ Ultimo canale". */
    fun lastChannel() {
        val target = zapper?.back() ?: return
        scheduleZap(target)
    }

    /**
     * The overlay updates instantly; the old stream is closed right away and the new one opens
     * only after 500 ms without further presses.
     */
    private fun scheduleZap(target: Channel) {
        showChannelInfo(target)
        pendingZapIndex = _ui.value.channels.indexOfFirst { it.channel.id == target.id }.takeIf { it >= 0 }
        _ui.update { it.copy(hasLastChannel = zapper?.lastChannel != null) }
        flashBanner()
        openJob?.cancel()
        retryJob?.cancel()
        stopLocal()
        releaseLease()
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(500)
            pendingZapIndex = null
            request = request?.copy(itemId = target.id, kind = PlayKind.LIVE)
            runCatching { playChannel(target) }.onFailure { e ->
                showFinalError(PlaybackError(PlaybackErrorKind.UNKNOWN, context.getString(R.string.player_error), errorMessage(context, e), true))
            }
        }
    }

    /** Remote number entry: "2", "0", "1" → channel 201 after 1.8 s (or at 4 digits). */
    fun typeDigit(digit: Int) {
        val z = zapper ?: return
        if (!_ui.value.isLive) return
        val complete = z.typeDigit(digit, System.currentTimeMillis())
        _ui.update { it.copy(pendingNumber = z.pendingNumber) }
        numberJob?.cancel()
        numberJob = scope.launch {
            if (!complete) delay(1_850)
            commitNumber(force = complete)
        }
    }

    private suspend fun commitNumber(force: Boolean) {
        val z = zapper ?: return
        val n = z.commitIfDue(System.currentTimeMillis(), force)
        _ui.update { it.copy(pendingNumber = "") }
        if (n == null) return
        val req = request ?: return
        val target = z.channelForNumber(n) ?: core.catalog.channelByNumber(req.sourceId, n) ?: return
        if (_ui.value.channels.none { it.channel.id == target.id }) {
            // Different category: switch the zapping list to the target's category.
            loadChannelList(req.sourceId, target.categoryId, target)
        }
        scheduleZap(target)
    }

    fun playChannelFromList(ch: Channel) {
        cancelPending()
        zapper?.select(ch)
        request = request?.copy(itemId = ch.id, kind = PlayKind.LIVE)
        _ui.update { it.copy(drawerOpen = false, error = null) }
        openJob = scope.launch {
            runCatching { playChannel(ch) }.onFailure { e ->
                showFinalError(PlaybackError(PlaybackErrorKind.UNKNOWN, context.getString(R.string.player_error), errorMessage(context, e), true))
            }
        }
        flashBanner()
    }

    private fun flashBanner() {
        bannerJob?.cancel()
        _ui.update { it.copy(zapBanner = true) }
        bannerJob = scope.launch { delay(4_000); _ui.update { it.copy(zapBanner = false) } }
    }

    fun showControls() {
        userInteraction()
        _interaction.value = System.nanoTime()
        _ui.update { it.copy(controlsVisible = true) }
    }

    fun showControlsIfVisible() {
        if (_ui.value.controlsVisible) _interaction.value = System.nanoTime()
    }

    fun hideControls() = _ui.update { it.copy(controlsVisible = false) }

    /** INFO key: mini-guide for 4 s. */
    fun showInfo() = flashBanner()
    fun toggleControls() {
        userInteraction()
        _ui.update { it.copy(controlsVisible = !it.controlsVisible) }
    }
    fun setDrawer(open: Boolean) = _ui.update { it.copy(drawerOpen = open, controlsVisible = if (open) false else it.controlsVisible) }
    fun setPip(pip: Boolean) = _ui.update { it.copy(inPip = pip, controlsVisible = if (pip) false else it.controlsVisible) }

    fun setAspect(mode: Int) {
        app.prefs.aspectMode = mode
        _ui.update { it.copy(aspectMode = mode) }
    }

    fun setSpeed(speed: Float) {
        exo.playbackParameters = exo.playbackParameters.withSpeed(speed)
        _ui.update { it.copy(speed = speed) }
    }

    private fun updateTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val text = mutableListOf<TrackOption>()
        val audioInfo = mutableListOf<TrackInfo>()
        val textInfo = mutableListOf<TrackInfo>()
        tracks.groups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                if (group.type == C.TRACK_TYPE_AUDIO && audioKey(f) in failedAudio) continue
                val info = TrackInfo(
                    id = "$gi:$ti",
                    language = f.language,
                    // Role flags from the stream (HLS/DASH/MKV) feed the "non udenti" / "commento" variants.
                    label = listOfNotNull(
                        f.label,
                        "SDH".takeIf { f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 },
                        "commentary".takeIf { f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0 },
                        "audio description".takeIf { f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 },
                    ).joinToString(" ").ifEmpty { null },
                    channelCount = f.channelCount.coerceAtLeast(0),
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        audio += TrackOption(gi, ti, TrackPicker.displayName(info, audioInfo.size), group.isTrackSelected(ti))
                        audioInfo += info
                    }
                    C.TRACK_TYPE_TEXT -> {
                        text += TrackOption(gi, ti, TrackPicker.displayName(info, textInfo.size), group.isTrackSelected(ti))
                        textInfo += info
                    }
                }
            }
        }
        // One entry per language (HLS repeats renditions per codec group, MKV often has 5.1 + 2.0 of the same
        // language); the track in use always stays in the list.
        fun dedupe(options: MutableList<TrackOption>, infos: MutableList<TrackInfo>) {
            val selectedId = options.indexOfFirst { it.selected }.takeIf { it >= 0 }?.let { infos[it].id }
            val keep = TrackPicker.distinct(infos, selectedId).map { it.id }.toSet()
            val kept = infos.indices.filter { infos[it].id in keep }
            val o = kept.map { options[it] }; val i = kept.map { infos[it] }
            options.clear(); options += o.mapIndexed { n, opt -> opt.copy(label = TrackPicker.displayName(i[n], n)) }
            infos.clear(); infos += i
        }
        dedupe(audio, audioInfo)
        dedupe(text, textInfo)
        if (failedAudio.isNotEmpty() && audio.none { it.selected }) {
            // The failing track was the one in use: switch to a working one, or play without audio.
            if (audio.isEmpty()) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                _ui.update { it.copy(notice = "Nessuna traccia audio di questo contenuto è supportata da questo dispositivo: riproduco senza audio.") }
            } else {
                applyTrack(C.TRACK_TYPE_AUDIO, audio.first())
            }
        }
        val textEnabled = !exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        _ui.update { it.copy(audioTracks = audio, textTracks = text, textEnabled = textEnabled) }
        if (!autoTracksApplied && (audioInfo.isNotEmpty() || textInfo.isNotEmpty())) {
            autoTracksApplied = true
            autoSelect(audio, audioInfo, text, textInfo)
        }
    }

    /** Preferred audio/subtitle languages (shared rules); manual choices win for the session. */
    private fun autoSelect(audio: List<TrackOption>, audioInfo: List<TrackInfo>, text: List<TrackOption>, textInfo: List<TrackInfo>) {
        val langs = LocaleList.getDefault().let { l -> (0 until l.size()).map { l[it].language } }
        val pickedAudio = if (manualAudio) null else TrackPicker.pickAudio(audioInfo, settings, langs)
        pickedAudio?.let { pick ->
            val opt = audio[audioInfo.indexOf(pick)]
            if (!opt.selected) applyTrack(C.TRACK_TYPE_AUDIO, opt)
        }
        if (!manualText) {
            val selectedAudio = pickedAudio ?: audio.indexOfFirst { it.selected }.takeIf { it >= 0 }?.let { audioInfo[it] }
            val sub = TrackPicker.pickSubtitle(textInfo, selectedAudio, settings, langs)
            applyTrack(C.TRACK_TYPE_TEXT, sub?.let { text[textInfo.indexOf(it)] })
        }
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    private fun audioKey(f: Format) = "${f.id}|${f.sampleMimeType}|${f.language}|${f.channelCount}|${f.label}"

    /** Manual choice from the picker: remembered for the rest of the session. */
    fun selectTrack(type: Int, option: TrackOption?) {
        if (type == C.TRACK_TYPE_AUDIO) manualAudio = true else manualText = true
        applyTrack(type, option)
        userInteraction()
    }

    private fun applyTrack(type: Int, option: TrackOption?) {
        val builder = exo.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = exo.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        exo.trackSelectionParameters = builder.build()
    }

    // ------------------------------------------------------------------ position, health, progress

    private suspend fun pollPosition() {
        while (scope.isActive) {
            if (foreground && !released) {
                val p = player
                _position.value = PlaybackPosition(p.currentPosition.coerceAtLeast(0), p.bufferedPosition.coerceAtLeast(0))
                val d = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
                if (d != null && d != _ui.value.durationMs) _ui.update { it.copy(durationMs = d) }
            }
            delay(500)
        }
    }

    /** 1 s health check: stall watchdog + retry-budget reset after 30 s of healthy playback. */
    private suspend fun healthLoop() {
        while (scope.isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            tickSession(now)
            val s = stream ?: continue
            if (released || casting || (lease == null && !s.isLocal())) { healthySince = 0; continue }
            if (_ui.value.statsVisible) updateStats()
            // Rebuffer > 1 s counts as a stall for the stats and the buffer advisor.
            if (bufferingSince > 0 && !stallReported && now - bufferingSince > 1_000 && exo.playbackState == Player.STATE_BUFFERING) {
                stallReported = true
                stallCount++
                if (app.bufferAdvisor(settings.bufferMode).onStall(now)) {
                    android.widget.Toast.makeText(context, R.string.buffer_increased, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            val state = exo.playbackState
            val wants = exo.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE
            val position = if (s.isLive) exo.currentPosition + exo.bufferedPosition else exo.currentPosition
            if (watchdog.tick(now, position, wants)) {
                resumePositionMs = if (s.isLive) 0 else exo.currentPosition
                stopLocal()
                releaseLease()
                watchdog.reset()
                handleFailure(PlaybackErrors.stalled())
                continue
            }
            if (exo.isPlaying) {
                if (healthySince == 0L) healthySince = now
                else if (now - healthySince > 30_000) { retryPolicy.reset(); healthySince = now }
            } else healthySince = 0
        }
    }

    private suspend fun saveProgressLoop() {
        while (scope.isActive) {
            delay(10_000)
            if (player.isPlaying) saveProgressNow()
        }
    }

    fun saveProgressNow() {
        if (released) return
        val p = player
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        movie?.let { m ->
            app.appScope.launch { core.library.saveProgress(m.sourceId, ContentType.MOVIE, m.id, null, m.name, m.year?.toString(), m.poster, pos, dur) }
        }
        episode?.let { ep ->
            val ser = series
            app.appScope.launch {
                core.library.saveProgress(ep.sourceId, ContentType.EPISODE, ep.id, ep.seriesId, ser?.name ?: ep.title, episodeLabel(ep), ep.image ?: ser?.cover, pos, dur)
            }
        }
    }

    // ------------------------------------------------------------------ session timer & stats

    /** Any key press, touch, zap or menu action. */
    fun userInteraction() {
        sessionTimer.onInteraction(System.currentTimeMillis())
        if (_ui.value.session is SessionState.AskStillWatching) _ui.update { it.copy(session = null) }
    }

    private fun tickSession(now: Long) {
        if (stream == null || released || !(exo.isPlaying || casting)) return
        when (val st = sessionTimer.tick(now)) {
            SessionState.Running -> if (_ui.value.session != null) _ui.update { it.copy(session = null) }
            is SessionState.Stop -> {
                // Connection rules: stop the player and give the connection back right now.
                cancelPending()
                stopLocal()
                releaseLease()
                castPlayer?.takeIf { casting }?.stop()
                _ui.update { it.copy(session = null, stoppedReason = st.reason, sleepEndsAt = null) }
            }
            else -> _ui.update { it.copy(session = st, sleepEndsAt = sessionTimer.sleepRemainingMs(now)?.let { r -> now + r }) }
        }
    }

    /** minutes = 0 → off; -1 → end of the current programme (live). */
    fun setSleepTimer(minutes: Int) {
        val now = System.currentTimeMillis()
        when {
            minutes == 0 -> sessionTimer.cancelSleep()
            minutes < 0 -> _ui.value.now?.endAt?.let { sessionTimer.setSleepAt(it) } ?: return
            else -> sessionTimer.setSleepTimer(minutes, now)
        }
        _ui.update { it.copy(sleepEndsAt = sessionTimer.sleepRemainingMs(now)?.let { r -> now + r }, session = null) }
        userInteraction()
    }

    fun extendSleep(minutes: Int) {
        val now = System.currentTimeMillis()
        sessionTimer.extendSleep(minutes, now)
        _ui.update { it.copy(session = null, sleepEndsAt = sessionTimer.sleepRemainingMs(now)?.let { r -> now + r }) }
    }

    fun cancelSleep() {
        sessionTimer.cancelSleep()
        _ui.update { it.copy(session = null, sleepEndsAt = null) }
    }

    fun toggleStats() {
        _ui.update { it.copy(statsVisible = !it.statsVisible) }
        if (_ui.value.statsVisible) updateStats()
    }

    private fun updateStats() {
        val v = exo.videoFormat
        val a = exo.audioFormat
        fun codec(mime: String?, codecs: String?) =
            codecs?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: mime?.substringAfter('/') ?: "—"
        _ui.update {
            it.copy(
                stats = PlayerStats(
                    resolution = v?.let { f -> if (f.width > 0) "${f.width}×${f.height}" else null } ?: "—",
                    fps = v?.frameRate?.takeIf { f -> f > 0 }?.let { f -> String.format(Locale.ROOT, "%.2f", f) } ?: "—",
                    videoCodec = codec(v?.sampleMimeType, v?.codecs),
                    audioCodec = codec(a?.sampleMimeType, a?.codecs) + (a?.channelCount?.takeIf { c -> c > 0 }?.let { c -> " · ${c}ch" } ?: ""),
                    bitrateKbps = (v?.bitrate?.takeIf { b -> b > 0 } ?: v?.averageBitrate?.takeIf { b -> b > 0 })?.let { b -> b / 1000L },
                    bufferSec = ((exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0)) / 1000f,
                    droppedFrames = droppedFrames,
                    stalls = stallCount,
                    networkKbps = networkBps?.let { b -> b / 1000 },
                    bufferProfile = currentProfile.name,
                ),
            )
        }
    }

    private fun observeSettings() {
        scope.launch {
            core.playerPreferences.settings.collect { st ->
                val hoursChanged = st.stillWatchingAfterHours != settings.stillWatchingAfterHours
                settings = st
                if (hoursChanged) {
                    val sleepLeft = sessionTimer.sleepRemainingMs(System.currentTimeMillis())
                    sessionTimer = WatchSessionTimer(st.stillWatchingAfterHours).also { t ->
                        t.start(System.currentTimeMillis())
                        sleepLeft?.let { left -> t.setSleepAt(System.currentTimeMillis() + left) }
                    }
                }
                _ui.update { it.copy(subtitleStyle = st.subtitleStyle, statsVisible = it.statsVisible || st.showStatsOverlay) }
            }
        }
    }

    // ------------------------------------------------------------------ cast

    private fun setupCast() {
        val ready = CastSupport.castContext(context)
        if (ready != null) setupCast(ready)
        // Framework still loading (first seconds after app start): attach when it's ready.
        else scope.launch { CastSupport.ready.filterNotNull().first().let { if (!released) setupCast(it) } }
    }

    private fun setupCast(castContext: com.google.android.gms.cast.framework.CastContext) {
        if (castPlayer != null) return
        val cp = runCatching { CastPlayer(castContext) }.getOrNull() ?: return
        castPlayer = cp
        val listener = object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                val name = castContext.sessionManager.currentCastSession?.castDevice?.friendlyName ?: "Chromecast"
                val s = stream
                if (s == null) {
                    // Still opening: the start in progress goes straight to the Chromecast.
                    _ui.update { it.copy(castDevice = name) }
                    return
                }
                // The Chromecast opens its own connection: close ours first, then take a "Chromecast" lease.
                val pos = if (_ui.value.isLive) 0 else exo.currentPosition
                cancelPending()
                stopLocal()
                releaseLease()
                _ui.update { it.copy(castDevice = name) }
                launchStart(s, pos)
            }

            override fun onCastSessionUnavailable() {
                val pos = if (_ui.value.isLive) 0 else cp.currentPosition
                releaseLease()
                _ui.update { it.copy(castDevice = null) }
                val s = stream ?: return
                if (foreground) launchStart(s, pos)
                else {
                    // Cast ended while the player is in the background: don't open the stream on the
                    // phone behind the user's back; resume when the player comes back (onStart).
                    resumePositionMs = pos
                    stoppedInBackground = true
                }
            }
        }
        cp.setSessionAvailabilityListener(listener)
        // Already connected to a Chromecast when the player opened: hand off right away.
        if (cp.isCastSessionAvailable) listener.onCastSessionAvailable()
    }

    private fun loadOnCast(startMs: Long) {
        val cp = castPlayer ?: return
        val s = stream ?: return
        if (s.isLocal()) return // local downloads can't be cast
        val mime = mimeFor(s) ?: when (s.container) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts" -> MimeTypes.VIDEO_MP2T
            else -> MimeTypes.VIDEO_MP4
        }
        val ui = _ui.value
        val item = MediaItem.Builder()
            .setUri(s.url)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ui.title)
                    .setSubtitle(ui.subtitle ?: ui.now?.title)
                    .setArtworkUri(ui.artwork?.let(Uri::parse))
                    .setMediaType(if (s.isLive) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MOVIE)
                    .build(),
            )
            .build()
        cp.setMediaItem(item, startMs.coerceAtLeast(0))
        cp.playWhenReady = true
        cp.prepare()
        _ui.update { it.copy(buffering = false) }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Activity stopped (background, screen off, PiP closed): close the stream unless casting. */
    fun onStop() {
        foreground = false
        if (released) return
        saveProgressNow()
        if (casting) return
        // Only while a stream is loaded: after an error the player is empty and would report 0,
        // and coming back would restart the film from the beginning.
        stream?.let { if (!it.isLive && exo.currentMediaItem != null) resumePositionMs = exo.currentPosition.coerceAtLeast(0) }
        cancelPending()
        stopLocal()
        releaseLease()
        stoppedInBackground = stream != null
    }

    /** Back in foreground after [onStop]: reopen the same stream (VOD resumes where it was). */
    fun onStart() {
        foreground = true
        if (!stoppedInBackground || released) return
        stoppedInBackground = false
        val s = stream ?: return
        if (_ui.value.error != null || _ui.value.busy != null) return
        launchStart(s, if (s.isLive) 0 else resumePositionMs)
    }

    /** VOD position to restore if the process is killed in the background (null for live). */
    fun restorePositionMs(): Long? {
        val s = stream ?: return null
        if (s.isLive || request?.kind == PlayKind.LIVE) return null
        return if (exo.currentMediaItem != null && !stoppedInBackground) exo.currentPosition.coerceAtLeast(0) else resumePositionMs
    }

    /** Idempotent. */
    fun release() {
        if (released) return
        saveProgressNow()
        cancelPending()
        epgJob?.cancel()
        stopLocal()
        releaseLease()
        released = true
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        exo.removeListener(listener)
        exo.removeAnalyticsListener(analytics)
        exo.release()
    }

    init {
        // Declared last so every property (including the listener) is initialised first.
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        sessionTimer.start(System.currentTimeMillis())
        observeSettings()
        setupCast()
        scope.launch { pollPosition() }
        scope.launch { healthLoop() }
        scope.launch { saveProgressLoop() }
    }
}
