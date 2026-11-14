package com.steplayer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.steplayer.app.ui.nav.AppNavHost
import com.steplayer.app.ui.nav.Routes
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.theme.StePlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FragmentActivity (not plain ComponentActivity) because the Cast MediaRouteButton
 * needs a FragmentManager to show its device picker.
 */
class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_GUIDE_SOURCE = "guide_source"
        const val EXTRA_GUIDE_CATEGORY = "guide_category"
        const val EXTRA_CONNTEST_SOURCE = "conntest_source"
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readGuideRequest(intent)
    }

    private fun readGuideRequest(intent: android.content.Intent?) {
        val src = intent?.getLongExtra(EXTRA_GUIDE_SOURCE, -1L) ?: -1L
        val cat = intent?.getStringExtra(EXTRA_GUIDE_CATEGORY)
        if (src >= 0 && cat != null) pendingRoute = "guide/$src/${android.net.Uri.encode(cat)}"
        val test = intent?.getLongExtra(EXTRA_CONNTEST_SOURCE, -1L) ?: -1L
        if (test >= 0) pendingRoute = "conntest/$test"
    }

    private var startDestination by mutableStateOf<String?>(null)

    /** Route requested by the player (GUIDE key → guide, "Perché si blocca?" → connection test). */
    private var pendingRoute by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { startDestination == null }

        readGuideRequest(intent)
        lifecycleScope.launch {
            // Core + prefs touch the disk (DB, Keystore, SharedPreferences): read them off the main
            // thread while the splash screen stays up.
            val (source, accepted) = withContext(Dispatchers.IO) { app.core.sources.current() to app.prefs.disclaimerAccepted }
            startDestination = when {
                source == null || !accepted -> Routes.ONBOARDING
                else -> Routes.MAIN
            }
            // Refresh the catalog in the background when stale (never blocks the UI).
            if (source != null) app.appScope.launch { runCatching { app.core.sync.syncIfStale(source.id) } }
            if (!app.isTv && Build.VERSION.SDK_INT >= 33 && accepted &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            StePlayerTheme {
                CompositionLocalProvider(LocalIsTv provides app.isTv) {
                    Box(Modifier.fillMaxSize().background(Palette.Background)) {
                        startDestination?.let { AppNavHost(it, pendingRoute, onRouteHandled = { pendingRoute = null }) }
                    }
                }
            }
        }
    }
}
