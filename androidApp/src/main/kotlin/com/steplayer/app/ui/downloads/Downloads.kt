package com.steplayer.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.download.DownloadSpeeds
import com.steplayer.app.download.SpeedInfo
import com.steplayer.app.player.PlayerLauncher
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class DownloadsViewModel(private val app: StePlayerApp) : ViewModel() {
    val downloads: StateFlow<List<Download>?> = app.core.downloads.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val speeds: StateFlow<Map<String, SpeedInfo>> = DownloadSpeeds.state

    // Off the main thread (resume builds the stream URL from Keystore credentials); never crash on I/O errors.
    fun pause(d: Download) = viewModelScope.launch(Dispatchers.Default) { runCatching { app.core.downloads.pause(d.id) } }
    fun resume(d: Download) = viewModelScope.launch(Dispatchers.Default) { runCatching { app.core.downloads.resume(d.id) } }
    fun delete(d: Download) = viewModelScope.launch(Dispatchers.Default) { runCatching { app.core.downloads.delete(d.id) } }
}

@Composable
fun DownloadsScreen(actions: AppActions) {
    val vm = appViewModel { DownloadsViewModel(it) }
    val list by vm.downloads.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val restricted = remember { isBackgroundRestricted(context) }
    var confirm by remember { mutableStateOf<Download?>(null) }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.nav_downloads), large = true)
        val items = list
        if (items != null && items.isEmpty()) {
            EmptyState(Icons.Rounded.Download, stringResource(R.string.downloads_empty))
        } else if (items != null) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val active = items.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
                if (restricted && active) {
                    item(key = "restricted", contentType = "tip") { BackgroundRestrictedTip { openAppSettings(context) } }
                }
                items(items, key = { it.id }, contentType = { "download" }) { d ->
                    DownloadRow(
                        d,
                        speeds[d.id],
                        onPlay = {
                            if (d.status == DownloadStatus.COMPLETED) {
                                when (d.type) {
                                    ContentType.EPISODE -> PlayerLauncher.episode(context, d.sourceId, d.parentId.orEmpty(), d.itemId, -1, offlineTitle = d.title, offlineSubtitle = d.subtitle, offlineExt = d.extension)
                                    else -> PlayerLauncher.movie(context, d.sourceId, d.itemId, -1, offlineTitle = d.title, offlineExt = d.extension)
                                }
                            }
                        },
                        onPause = { vm.pause(d) },
                        onResume = { vm.resume(d) },
                        onDelete = { confirm = d },
                    )
                }
            }
        }
    }

    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.delete_download_q)) },
            text = { Text(listOfNotNull(d.title, d.subtitle).joinToString(" · ")) },
            confirmButton = { TextButton(onClick = { vm.delete(d); confirm = null }) { Text(stringResource(R.string.delete), color = Palette.Live) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
            containerColor = Palette.SurfaceElevated,
        )
    }
}

@Composable
private fun DownloadRow(d: Download, speed: SpeedInfo?, onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(shape, 1.02f)
            .clip(shape)
            .background(Palette.Surface)
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            ArtImage(d.image, d.title, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        // Fixed set of lines whatever the state (title, subtitle, status, progress slot), so a row
        // never changes height while it downloads, pauses or completes.
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                d.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                downloadStatusLine(
                    d, speed,
                    queued = stringResource(R.string.status_queued),
                    paused = stringResource(R.string.status_paused),
                    completed = stringResource(R.string.status_completed),
                    failed = stringResource(R.string.status_failed),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = when (d.status) {
                    DownloadStatus.COMPLETED -> Palette.Success
                    DownloadStatus.FAILED -> Palette.Live
                    else -> Palette.TextSecondary
                },
                // A failed row doesn't change any more: room for the whole reason.
                maxLines = if (d.status == DownloadStatus.FAILED) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // Slot always reserved; the bar is only drawn while it means something.
            val showBar = d.status == DownloadStatus.RUNNING || d.status == DownloadStatus.PAUSED || d.status == DownloadStatus.QUEUED
            ProgressBar(d.fraction, Modifier.fillMaxWidth().alpha(if (showBar) 1f else 0f), height = 4.dp)
        }
        Spacer(Modifier.width(6.dp))
        when (d.status) {
            DownloadStatus.COMPLETED -> RoundIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.play), onPlay, background = Palette.Accent.copy(alpha = 0.2f))
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> RoundIconButton(Icons.Rounded.Pause, stringResource(R.string.pause), onPause)
            DownloadStatus.PAUSED -> RoundIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.resume), onResume)
            DownloadStatus.FAILED -> RoundIconButton(Icons.Rounded.Refresh, stringResource(R.string.retry), onResume)
        }
        RoundIconButton(Icons.Rounded.Delete, stringResource(R.string.delete), onDelete, tint = Palette.TextSecondary)
    }
}

/**
 * One status line for every state (same wording on iOS):
 * "45% di 1,2 GB · 2,4 MB/s · 3 min", "In pausa · 300 MB di 1,2 GB", "In attesa · 1,2 GB",
 * "Completato · 1,2 GB", "Non riuscito · <motivo>".
 */
internal fun downloadStatusLine(d: Download, speed: SpeedInfo?, queued: String, paused: String, completed: String, failed: String): String {
    val total = d.totalBytes.takeIf { it > 0 }
    val progress = total?.let { "${Format.bytes(d.bytesDownloaded)} di ${Format.bytes(it)}" }
    val parts = when (d.status) {
        DownloadStatus.RUNNING -> buildList {
            // Size unknown (no Content-Length): no percentage, just what has arrived so far.
            // Short enough for a phone-width row: "24% di 1,2 GB · 2,4 MB/s · 3 min".
            if (total != null) add("${(d.fraction * 100).toInt()}% di ${Format.bytes(total)}") else add(Format.bytes(d.bytesDownloaded))
            speed?.takeIf { it.bytesPerSecond > 0 }?.let { s ->
                add(Format.speed(s.bytesPerSecond))
                s.etaSeconds?.let { add(Format.remaining(it)) }
            }
        }
        DownloadStatus.PAUSED -> listOfNotNull(paused, progress)
        DownloadStatus.QUEUED -> listOfNotNull(queued, total?.let(Format::bytes))
        DownloadStatus.COMPLETED -> listOfNotNull(completed, total?.let(Format::bytes))
        DownloadStatus.FAILED -> listOfNotNull(failed, d.error?.takeIf { it.isNotBlank() })
    }
    return parts.joinToString(" · ")
}

@Composable
private fun BackgroundRestrictedTip(onOpenSettings: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().focusHighlight(shape, 1.02f).clip(shape)
            .background(Palette.Live.copy(alpha = 0.15f)).clickable(onClick = onOpenSettings).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.BatteryAlert, null, tint = Palette.Live)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.downloads_restricted_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.downloads_restricted_text), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

/** True if the user (or a battery saver) set StePlayer to "Limitata": no background work at all. */
private fun isBackgroundRestricted(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 28 &&
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isBackgroundRestricted

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
