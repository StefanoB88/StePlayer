package com.steplayer.app.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.steplayer.app.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Uses Google's Default Media Receiver: no custom receiver app needs to be registered. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object CastSupport {
    private val _context = MutableStateFlow<CastContext?>(null)

    /** The CastContext once it is ready; null before, and always on TV / without Google Play services. */
    val ready: StateFlow<CastContext?> = _context.asStateFlow()

    private val started = AtomicBoolean(false)

    /**
     * Starts loading the Cast framework in the background. The synchronous getSharedInstance() loads a
     * Play-services module on the calling thread (~0.5–1 s the first time), which froze the UI when the
     * first player or detail page opened. Call from any thread; idempotent.
     */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        if (app.app.isTv || !started.compareAndSet(false, true)) return
        val gms = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) }.getOrDefault(-1)
        if (gms != ConnectionResult.SUCCESS) return
        // The async API must be called on the main thread; the module itself loads on [executor].
        Handler(Looper.getMainLooper()).post {
            runCatching {
                CastContext.getSharedInstance(app, executor)
                    .addOnSuccessListener { _context.value = it }
            }
        }
    }

    /** Non-blocking: null until [warmUp] finished. */
    fun castContext(context: Context): CastContext? {
        if (_context.value == null) warmUpAsync(context)
        return _context.value
    }

    private fun warmUpAsync(context: Context) {
        if (started.get()) return
        context.app.appScope.launch { warmUp(context) }
    }

    private val executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "cast-init").apply { isDaemon = true } } }
}

/** The standard Cast icon; opens the device picker. Hidden when Cast isn't available. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val castContext by CastSupport.ready.collectAsState()
    LaunchedEffect(Unit) { CastSupport.castContext(context) }
    if (castContext == null) return
    AndroidView(
        modifier = modifier.size(44.dp),
        factory = { ctx ->
            // MediaRouteButton needs an AppCompat theme for its chooser dialog.
            val themed = ContextThemeWrapper(ctx, com.steplayer.app.R.style.Theme_StePlayer_Cast)
            MediaRouteButton(themed).also { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it) }
        },
    )
}
