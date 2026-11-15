package com.steplayer.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steplayer.app.R
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.rememberApp
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import com.steplayer.shared.model.Source
import com.steplayer.shared.pairing.SendToTvState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * "Invia una lista alla TV": for TVs that can't open a server (Samsung), this phone serves its lists on the
 * local network for 10 minutes; the TV finds it and asks for them with the 4-digit code shown here.
 */
@Composable
fun SendToTvScreen(actions: AppActions) {
    val app = rememberApp()
    val send = app.core.sendToTv
    val state by send.state.collectAsStateWithLifecycle(SendToTvState.Idle)
    var sources by remember { mutableStateOf<List<Source>?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var attempt by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        val all = runCatching { app.core.sources.sources.first() }.getOrDefault(emptyList())
        val active = runCatching { app.core.sources.current() }.getOrNull()
        sources = all
        selected = setOfNotNull(active?.id ?: all.firstOrNull()?.id)
    }
    LaunchedEffect(sources, attempt) {
        if (!sources.isNullOrEmpty()) send.start(selected.toList(), Build.MODEL.orEmpty().ifBlank { "Telefono" })
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    DisposableEffect(Unit) { onDispose { send.stop() } }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.send_tv_title), onBack = actions::back)
        val list = sources
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Accent) }
            list.isEmpty() -> EmptyState(Icons.Rounded.ErrorOutline, stringResource(R.string.send_tv_no_lists))
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).navigationBarsPadding(),
            ) {
                Text(stringResource(R.string.send_tv_step1), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.send_tv_step2), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.send_tv_step3), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                when (val s = state) {
                    is SendToTvState.Waiting -> {
                        val expired = now >= s.expiresAtMs
                        CodeBoxes(s.code)
                        Spacer(Modifier.height(16.dp))
                        if (expired) {
                            Text(stringResource(R.string.pair_expired), color = Palette.TextSecondary)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.send_tv_waiting), color = Palette.TextSecondary)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.pair_expires_in, Format.duration(s.expiresAtMs - now)),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.send_tv_address, s.address), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                        }
                    }
                    is SendToTvState.Sent -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Success, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.send_tv_sent), style = MaterialTheme.typography.titleMedium)
                    }
                    is SendToTvState.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Live, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SendToTvState.Idle -> CircularProgressIndicator(color = Palette.Accent)
                }
                if (state is SendToTvState.Failed || (state as? SendToTvState.Waiting)?.let { now >= it.expiresAtMs } == true) {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(stringResource(R.string.send_tv_retry), onClick = { attempt++ })
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.send_tv_lists), style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(vertical = 6.dp)) {
                    list.forEach { src ->
                        val checked = src.id in selected
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .focusHighlight(shape, 1.01f)
                                .clip(shape)
                                .clickable {
                                    selected = if (checked) selected - src.id else selected + src.id
                                    send.select(selected.toList())
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, uncheckedColor = Palette.TextSecondary),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(src.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.send_tv_privacy), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun CodeBoxes(code: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        code.forEach { d ->
            Box(
                Modifier.size(width = 64.dp, height = 80.dp).clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(d.toString(), style = MaterialTheme.typography.displaySmall, color = Palette.Accent2, textAlign = TextAlign.Center)
            }
        }
    }
}
