package com.steplayer.app.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.session.MediaSession
import com.steplayer.app.MainActivity
import com.steplayer.app.app
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.StePlayerTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen player for live, VOD, episodes and catch-up.
 * Remote: OK = controls, up/down = next/previous channel, 0–9 = channel number, LAST = last channel,
 * left = channel list (live) / −10 s (VOD), right = +10 s (VOD), GUIDE = TV guide, INFO = mini-guide.
 */
class PlayerActivity : FragmentActivity() {

    private lateinit var controller: PlayerController
    private var session: MediaSession? = null
    private val isTv by lazy { app.isTv }
    private var originalModeId = 0
    /** Video area in window coordinates: PiP source rect hint (smooth enter animation, no black flash). */
    private var videoBounds: android.graphics.Rect? = null
    /** Left PiP without coming back to the foreground yet: if onStop follows, the PiP window was closed. */
    private var leftPip = false
    /** PiP really available: not on TV, declared by the system, and not on "low RAM" phones (Android disables it there). */
    private val pipSupported by lazy {
        !isTv && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !(getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        originalModeId = window.attributes.preferredDisplayModeId

        controller = PlayerController(
            context = this,
            app = app,
            scope = lifecycleScope,
            onRevokedExternally = { finish() },
            onVideoFormat = ::matchFrameRate,
            onPlayerRebuilt = { p -> session?.player = p },
        )
        session = runCatching { MediaSession.Builder(this, controller.exo).build() }.getOrNull()

        val fromIntent = PlayRequest.from(intent)
        if (fromIntent == null) { finish(); return }
        // Recreated after process death: resume VOD where it was, not at the intent's start position.
        val restored = savedInstanceState?.getLong(STATE_POSITION, -1L) ?: -1L
        val request = if (restored >= 0 && fromIntent.kind != PlayKind.LIVE) fromIntent.copy(startMs = restored) else fromIntent
        controller.open(request)
        observePipEligibility()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ui = controller.ui.value
                when {
                    ui.drawerOpen -> controller.setDrawer(false)
                    ui.nextEpisode != null -> controller.cancelNextEpisode()
                    isTv && ui.controlsVisible && !ui.blocking -> controller.hideControls()
                    else -> finish()
                }
            }
        })

        setContent {
            StePlayerTheme {
                CompositionLocalProvider(LocalIsTv provides isTv) {
                    PlayerScreen(
                        controller,
                        onBack = { finish() },
                        onPip = if (pipSupported) ::enterPip else null,
                        onDiagnose = ::openConnectionTest,
                        onStopped = { reason ->
                            android.widget.Toast.makeText(
                                this,
                                if (reason == com.steplayer.shared.playback.StopReason.SLEEP_TIMER) com.steplayer.app.R.string.stopped_sleep
                                else com.steplayer.app.R.string.stopped_inactivity,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            finish()
                        },
                        onVideoBounds = ::onVideoBounds,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::controller.isInitialized) controller.restorePositionMs()?.let { outState.putLong(STATE_POSITION, it) }
    }

    /** Android 12+: auto-enter PiP (smooth system animation) only while something is playing locally. */
    private fun observePipEligibility() {
        if (!pipSupported || Build.VERSION.SDK_INT < 31) return
        lifecycleScope.launch {
            controller.ui.map { it.isPlaying && it.castDevice == null && it.error == null }
                .distinctUntilChanged()
                .collect { updatePipParams(autoEnter = it) }
        }
    }

    private fun onVideoBounds(bounds: android.graphics.Rect) {
        if (!pipSupported || bounds == videoBounds || bounds.isEmpty) return
        videoBounds = bounds
        updatePipParams()
    }

    private var autoEnterPip = false

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        videoBounds?.let { b ->
            // 16:9 area centred in the video view (the window is letterboxed the same way).
            val w = minOf(b.width(), b.height() * 16 / 9)
            val h = w * 9 / 16
            val left = b.left + (b.width() - w) / 2
            val top = b.top + (b.height() - h) / 2
            builder.setSourceRectHint(android.graphics.Rect(left, top, left + w, top + h))
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnterPip).setSeamlessResizeEnabled(false)
        return builder.build()
    }

    private fun updatePipParams(autoEnter: Boolean = autoEnterPip) {
        autoEnterPip = autoEnter
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlayRequest.from(intent)?.let { controller.open(it) }
    }

    // Activity.dispatchKeyEvent is public platform API; androidx.core's ComponentActivity only marks its
    // own override as library-restricted (known lint false positive).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) controller.userInteraction()
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN) controller.showControlsIfVisible()
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val ui = controller.ui.value
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && ui.isLive) { controller.typeDigit(digit); return true }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { controller.zap(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { controller.zap(-1); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { controller.lastChannel(); return true }
            KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
            KeyEvent.KEYCODE_INFO -> { if (ui.isLive) controller.showInfo() else controller.showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                controller.togglePlay(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { controller.seekBy(30_000); controller.showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { controller.seekBy(-30_000); controller.showControls(); return true }
        }
        if (ui.controlsVisible || ui.drawerOpen || ui.blocking || ui.nextEpisode != null) return false
        return when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> {
                controller.showControls(); true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (ui.isLive) controller.zap(+1) else controller.showControls(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (ui.isLive) controller.zap(-1) else controller.showControls(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ui.isLive) controller.setDrawer(true) else { controller.seekBy(-10_000); controller.showControls() }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ui.isLive) controller.showInfo() else { controller.seekBy(10_000); controller.showControls() }
                true
            }
            KeyEvent.KEYCODE_SPACE -> { controller.togglePlay(); true }
            else -> false
        }
    }

    /** GUIDE key: close the player (releasing the connection) and open the TV guide. */
    private fun openGuide() {
        val req = PlayRequest.from(intent) ?: return
        val categoryId = controller.ui.value.channels.getOrNull(controller.ui.value.channelIndex)?.channel?.categoryId
            ?: req.categoryId ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_GUIDE_SOURCE, req.sourceId)
                .putExtra(MainActivity.EXTRA_GUIDE_CATEGORY, categoryId),
        )
        finish()
    }

    /** "Perché si blocca?": stop playback (the test takes the connection itself) and open the test. */
    private fun openConnectionTest() {
        val req = PlayRequest.from(intent) ?: return
        controller.release()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CONNTEST_SOURCE, req.sourceId),
        )
        finish()
    }

    /**
     * "Adatta frequenza schermo: Sempre" (API 30+): switch to a display mode at the same
     * resolution whose refresh rate is an integer multiple of the video frame rate.
     */
    private fun matchFrameRate(fps: Float, width: Int, height: Int) {
        if (!isTv || app.prefs.frameRateMode != 2 || Build.VERSION.SDK_INT < 30) return
        val display = display ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val best = candidates
            .filter { m -> val ratio = m.refreshRate / fps; abs(ratio - ratio.roundToInt()) < 0.01f && ratio.roundToInt() >= 1 }
            .minByOrNull { it.refreshRate } ?: return
        if (best.modeId != window.attributes.preferredDisplayModeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    private fun restoreDisplayMode() {
        if (window.attributes.preferredDisplayModeId != originalModeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
        }
    }

    private fun enterPip() {
        if (!pipSupported || isFinishing || isInPictureInPictureMode) return
        // Hide the controls first so they don't appear in the shrinking window.
        controller.setPip(true)
        // Some phones refuse PiP at runtime (battery saver, OEM limits, extreme video bounds): stay full screen.
        val ok = runCatching { enterPictureInPictureMode(pipParams()) }
            .recoverCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            .getOrDefault(false)
        if (!ok) {
            controller.setPip(false)
            android.widget.Toast.makeText(this, com.steplayer.app.R.string.pip_unavailable, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!::controller.isInitialized) return
        // Android 12+ enters PiP by itself (setAutoEnterEnabled), with the smoother system animation.
        if (Build.VERSION.SDK_INT >= 31) return
        val ui = controller.ui.value
        if (ui.isPlaying && ui.castDevice == null) enterPip()
    }

    @androidx.annotation.RequiresApi(31)
    override fun onPictureInPictureUiStateChanged(pipState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        // Android 15+: hide the controls as soon as the auto-enter animation starts.
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip && ::controller.isInitialized) controller.setPip(true)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::controller.isInitialized) return
        controller.setPip(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                // PiP window closed after onStop already ran: leave (connection already released).
                controller.release()
                finish()
            } else {
                leftPip = true // expanded (onResume follows) or being closed (onStop follows)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the player (back, error "Indietro", GUIDE…): save the position and close the stream
        // right now. onStop/onDestroy of a finishing activity only run once the screen below is idle
        // (seconds later, or up to 10 s), which kept the provider connection open meanwhile and let the
        // detail screen read the old resume position. Non-blocking: ExoPlayer is released in onDestroy.
        if (isFinishing && ::controller.isInitialized) controller.onStop()
    }

    override fun onResume() {
        super.onResume()
        leftPip = false
        // A PiP transition that was cancelled must not leave the controls disabled.
        if (::controller.isInitialized && !isInPictureInPictureMode && controller.ui.value.inPip) controller.setPip(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) controller.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (!::controller.isInitialized) return
        if (isInPictureInPictureMode || leftPip) {
            // The PiP window was closed: release everything and leave.
            controller.release()
            finish()
        } else {
            // Background / screen off: never keep a provider connection open (unless casting).
            controller.onStop()
        }
    }

    override fun onDestroy() {
        restoreDisplayMode()
        session?.release()
        session = null
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    private companion object {
        const val STATE_POSITION = "resume_position_ms"
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
