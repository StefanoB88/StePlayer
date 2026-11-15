package com.steplayer.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.StrictMode
import androidx.work.WorkInfo
import androidx.work.Configuration as WorkConfiguration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.steplayer.app.cast.CastSupport
import com.steplayer.app.download.AndroidDownloadEngine
import com.steplayer.app.ui.components.ArtworkSizeInterceptor
import com.steplayer.app.reminders.ExpiryScheduler
import com.steplayer.shared.StePlayerCore
import com.steplayer.shared.playback.BufferAdvisor
import com.steplayer.shared.settings.BufferMode
import com.steplayer.shared.platform.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

class StePlayerApp : Application(), SingletonImageLoader.Factory, WorkConfiguration.Provider {

    /**
     * Built lazily and warmed up on a background thread from [onCreate]: opening the credential
     * store (SharedPreferences + Keystore), the SQLite helper and Ktor/OkHttp (service loading from
     * the APK) all touch the disk. The first main-thread access just waits for the warm-up, which
     * normally finished long before (MainActivity reads the core off the main thread too).
     */
    val platform: AndroidPlatform by lazy { AndroidPlatform(this, userAgent) }
    val core: StePlayerCore by lazy {
        StePlayerCore(platform).also { it.downloads.engine = AndroidDownloadEngine(this) }
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: AppPrefs by lazy { AppPrefs(this) }

    val userAgent: String by lazy { "StePlayer/${BuildConfig.VERSION_NAME} (Android)" }

    /** Shared OkHttp client for playback (Media3), images (Coil) and downloads. */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Playback-only client: no idle keep-alive sockets (a lingering socket can keep a streaming account
     * "busy"), short timeouts, and no transparent retries (the player owns the retry policy).
     */
    val playbackHttp: OkHttpClient by lazy {
        // newBuilder(): shares the TLS setup of [okHttp] (building an SSLContext costs ~150 ms).
        okHttp.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val isTv: Boolean by lazy {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }

    private var advisor: Pair<BufferMode, BufferAdvisor>? = null

    /** One buffer advisor per app session (re-created only if the user changes the buffer mode). */
    @Synchronized
    fun bufferAdvisor(mode: BufferMode): BufferAdvisor {
        advisor?.let { (m, a) -> if (m == mode) return a }
        return BufferAdvisor(mode).also { advisor = mode to it }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        appScope.launch(Dispatchers.IO) {
            prefs // loads the SharedPreferences file
            core
            playbackHttp // TLS init, otherwise paid on the main thread by the first stream
            if (!isTv) {
                // Not needed for the first screen: start once the UI is up, so it doesn't compete
                // with the cold start for CPU (WorkManager DB, Cast module loading).
                delay(DEFERRED_INIT_MS)
                resumePendingDownloads()
                ExpiryScheduler.observe(this@StePlayerApp)
                CastSupport.warmUp(this@StePlayerApp)
            }
        }
    }

    /** Debug builds only: log disk/network work on the main thread and leaked closeables/activities. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .detectResourceMismatches()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .penaltyLog()
                .build(),
        )
    }

    /** Re-starts downloads that were queued/running when the process died and have no live work. */
    private fun resumePendingDownloads() = appScope.launch {
        runCatching {
            val wm = WorkManager.getInstance(this@StePlayerApp)
            core.downloads.pending().forEach { d ->
                val infos = wm.getWorkInfosForUniqueWork(d.id).get()
                if (infos.none { !it.state.isFinished || it.state == WorkInfo.State.SUCCEEDED }) {
                    core.downloads.resume(d.id)
                }
            }
        }
    }

    /** WorkManager is initialised on first use (off the main thread) instead of at process start. */
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Sized on the normal heap class, not the large one (largeHeap made it ~100 MB of bitmaps,
            // a lot on 1–2 GB TV boxes); scrolling back re-decodes quickly from the disk cache.
            .memoryCache { MemoryCache.Builder().maxSizeBytes(imageMemoryCacheBytes()).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            // TV boxes: no fade-in (every poster appearing while scrolling would redraw for 200 ms on a weak GPU);
            // low-RAM devices: RGB_565 posters (half the memory and bandwidth, no visible difference on artwork).
            .crossfade(!isTv)
            .allowRgb565(isLowRamDevice())
            .components {
                add(ArtworkSizeInterceptor())
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
            }
            .build()
}

private const val DEFERRED_INIT_MS = 3_000L

/** 1–2 GB TV boxes and low-end phones. */
private fun Context.isLowRamDevice(): Boolean {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return am.isLowRamDevice || am.memoryClass <= 192
}

private fun Context.imageMemoryCacheBytes(): Long {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return (am.memoryClass * 1024L * 1024L * 0.15).toLong().coerceIn(16L shl 20, 96L shl 20)
}

val Context.app: StePlayerApp get() = applicationContext as StePlayerApp
