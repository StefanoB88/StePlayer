package com.steplayer.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * Wall-clock time as Compose state, refreshed every [periodMs] (aligned to the period) while composed.
 *
 * Read it only inside draw lambdas, e.g. `ProgressBar({ program.progress(now.value) })`: a tick then
 * just redraws the progress bars instead of recomposing whole rows/lists.
 */
@Composable
fun rememberNow(periodMs: Long = 30_000): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            val t = System.currentTimeMillis()
            now.longValue = t
            delay(periodMs - t % periodMs)
        }
    }
    return now
}
