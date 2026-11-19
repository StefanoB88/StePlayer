package com.steplayer.app.ui.sync

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.onboarding.errorMessage
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.shared.model.SyncState
import com.steplayer.shared.model.SyncStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A sync the user asked for ("Aggiorna", "Sincronizza ora"): shown in a blocking dialog with the
 * progress until it ends. Automatic syncs (app start, stale catalog) stay silent in the background.
 */
class UserSync(private val app: StePlayerApp) {
    sealed interface Ui {
        val sourceId: Long
        data class Running(override val sourceId: Long) : Ui
        data class Failed(override val sourceId: Long, val message: String) : Ui
    }

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    fun start(sourceId: Long) {
        if (_ui.value is Ui.Running) return
        _ui.value = Ui.Running(sourceId)
        // App scope: the sync goes on even if the screen that started it goes away.
        app.appScope.launch {
            try {
                app.core.sync.sync(sourceId)
                _ui.value = null
                withContext(Dispatchers.Main) { app.toast(app.getString(R.string.refresh_done)) }
            } catch (e: CancellationException) {
                _ui.value = null
                throw e
            } catch (e: Throwable) {
                _ui.value = Ui.Failed(sourceId, errorMessage(app, e))
            }
        }
    }

    fun retry() { (_ui.value as? Ui.Failed)?.let { _ui.value = null; start(it.sourceId) } }

    fun dismiss() { if (_ui.value is Ui.Failed) _ui.value = null }
}

/** Placed once at the root of the app: the dialog covers every screen. */
@Composable
fun UserSyncDialogHost(app: StePlayerApp) {
    val ui by app.userSync.ui.collectAsStateWithLifecycle()
    val current = ui ?: return
    val state by app.core.sync.state.collectAsStateWithLifecycle(SyncState.Idle)
    val failed = current as? UserSync.Ui.Failed
    Dialog(
        onDismissRequest = { app.userSync.dismiss() }, // ignored while running
        properties = DialogProperties(dismissOnBackPress = failed != null, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.SurfaceElevated).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (failed != null) FailedContent(failed.message, app.userSync::retry, app.userSync::dismiss)
                else RunningContent(state as? SyncState.Running)
            }
        }
    }
}

@Composable
private fun RunningContent(running: SyncState.Running?) {
    val target = running?.progress ?: 0.02f
    val progress by animateFloatAsState(target, label = "sync")
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Palette.AccentGradient),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.CloudSync, null, tint = Palette.OnAccent, modifier = Modifier.size(34.dp)) }
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.refresh_dialog_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(stepLabel(running?.step)), color = Palette.TextSecondary, textAlign = TextAlign.Center)
    Spacer(Modifier.height(20.dp))
    ProgressBar({ progress }, Modifier.fillMaxWidth(), height = 8.dp)
    Spacer(Modifier.height(8.dp))
    Text("${(progress * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.refresh_dialog_wait), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, textAlign = TextAlign.Center)
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    // TV remote: focus lands on "Riprova" so the buttons are usable right away.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Live, modifier = Modifier.size(52.dp))
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.refresh_failed_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(message, color = Palette.TextSecondary, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(stringResource(R.string.close), onClick = onClose, modifier = Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.weight(1f).focusRequester(focus))
    }
}

fun stepLabel(step: SyncStep?): Int = when (step) {
    SyncStep.LIVE -> R.string.sync_live
    SyncStep.MOVIES -> R.string.sync_movies
    SyncStep.SERIES -> R.string.sync_series
    SyncStep.EPG -> R.string.sync_epg
    SyncStep.SAVING -> R.string.sync_saving
    else -> R.string.sync_auth
}
