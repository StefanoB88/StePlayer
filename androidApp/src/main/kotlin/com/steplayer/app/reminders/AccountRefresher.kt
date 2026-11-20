package com.steplayer.app.reminders

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.steplayer.app.StePlayerApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the active list's account up to date (expiry date, max connections) while the app is visible: once when it
 * comes to the foreground (also from the background), then every [INTERVAL_MS]. One login request each time, no
 * catalog download; the expiry banner and the reminders follow the sources table by themselves.
 */
object AccountRefresher {
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /** Call once from Application.onCreate (main thread). */
    fun install(app: StePlayerApp) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var job: Job? = null

            override fun onStart(owner: LifecycleOwner) {
                job?.cancel()
                job = app.appScope.launch {
                    while (isActive) {
                        val source = runCatching { app.core.sources.current() }.getOrNull()
                        if (source != null) runCatching { app.core.sync.refreshAccount(source.id) }
                        delay(INTERVAL_MS)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
                job = null
            }
        })
    }
}
