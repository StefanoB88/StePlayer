package com.steplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steplayer.app.R
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.rememberApp
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.shared.diagnostics.ConnectionTestState
import com.steplayer.shared.diagnostics.ConnectionVerdict
import com.steplayer.shared.settings.BufferMode
import com.steplayer.shared.settings.PlayerSettings
import com.steplayer.shared.settings.SubtitleBackground
import com.steplayer.shared.settings.SubtitleColor
import com.steplayer.shared.settings.SubtitleEdge
import com.steplayer.shared.settings.SubtitleMode
import kotlinx.coroutines.launch
import java.util.Locale

/** Languages offered for audio/subtitle preferences (ISO 639-1). null = follow the device. */
val PreferredLanguages: List<Pair<String?, String>> = listOf(
    null to "", "it" to "Italiano", "en" to "English", "es" to "Español", "fr" to "Français",
    "de" to "Deutsch", "pt" to "Português", "ro" to "Română", "ar" to "العربية",
)

@Composable
fun bufferLabel(mode: BufferMode) = stringResource(
    when (mode) {
        BufferMode.AUTO -> R.string.buffer_auto
        BufferMode.NORMAL -> R.string.buffer_normal
        BufferMode.LARGE -> R.string.buffer_large
    },
)

/** Chip-style single choice, D-pad friendly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val sel = value == selected
                val shape = RoundedCornerShape(50)
                // Fixed height, single line, text centred: scripts with tall glyphs (e.g. Arabic)
                // would otherwise make their chip taller than the others.
                Box(
                    Modifier.height(ChipHeight).focusHighlight(shape, 1.04f).clip(shape)
                        .then(if (sel) Modifier.background(Palette.AccentGradient) else Modifier.background(Palette.SurfaceElevated))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = ChipTextStyle(),
                        color = if (sel) Palette.OnAccent else Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val ChipHeight = 36.dp

/** labelLarge without font padding and with the line box trimmed, so every script centres alike. */
@Composable
private fun ChipTextStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(vertical = 8.dp), content = content)
}

// ---------------------------------------------------------------- Subtitles

@Composable
fun SubtitleSettingsScreen(actions: AppActions) {
    val app = rememberApp()
    val settings by app.core.playerPreferences.settings.collectAsStateWithLifecycle(PlayerSettings())
    val scope = rememberCoroutineScope()
    fun update(t: (PlayerSettings) -> PlayerSettings) = scope.launch { app.core.playerPreferences.update(t) }
    val style = settings.subtitleStyle
    val deviceLabel = stringResource(R.string.lang_device)

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.settings_subtitles), onBack = actions::back)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { SubtitlePreview(settings) }
                item {
                    Card {
                        ChoiceRow(
                            stringResource(R.string.sub_mode),
                            listOf(
                                SubtitleMode.OFF to stringResource(R.string.sub_mode_off),
                                SubtitleMode.AUTO to stringResource(R.string.sub_mode_auto),
                                SubtitleMode.ALWAYS to stringResource(R.string.sub_mode_always),
                            ),
                            settings.subtitleMode,
                        ) { m -> update { it.copy(subtitleMode = m) } }
                        ChoiceRow(
                            stringResource(R.string.sub_language),
                            PreferredLanguages.map { (code, name) -> code to (if (code == null) deviceLabel else name) },
                            settings.preferredSubtitleLanguage,
                        ) { c -> update { it.copy(preferredSubtitleLanguage = c) } }
                    }
                }
                item {
                    Card {
                        ChoiceRow(
                            stringResource(R.string.sub_size),
                            listOf(0.75f to "75%", 1.0f to "100%", 1.25f to "125%", 1.5f to "150%", 2.0f to "200%"),
                            style.scale,
                        ) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(scale = v)) } }
                        ChoiceRow(
                            stringResource(R.string.sub_color),
                            listOf(SubtitleColor.WHITE to stringResource(R.string.sub_color_white), SubtitleColor.YELLOW to stringResource(R.string.sub_color_yellow)),
                            style.color,
                        ) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(color = v)) } }
                        ChoiceRow(
                            stringResource(R.string.sub_background),
                            listOf(
                                SubtitleBackground.NONE to stringResource(R.string.sub_bg_none),
                                SubtitleBackground.SEMI_TRANSPARENT to stringResource(R.string.sub_bg_semi),
                                SubtitleBackground.OPAQUE to stringResource(R.string.sub_bg_opaque),
                            ),
                            style.background,
                        ) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(background = v)) } }
                        ChoiceRow(
                            stringResource(R.string.sub_edge),
                            listOf(
                                SubtitleEdge.NONE to stringResource(R.string.sub_edge_none),
                                SubtitleEdge.OUTLINE to stringResource(R.string.sub_edge_outline),
                                SubtitleEdge.SHADOW to stringResource(R.string.sub_edge_shadow),
                            ),
                            style.edge,
                        ) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(edge = v)) } }
                    }
                }
            }
        }
    }
}

/** Live preview over a neutral gradient "frame" with fictional text. */
@Composable
private fun SubtitlePreview(settings: PlayerSettings) {
    val st = settings.subtitleStyle
    val color = if (st.color == SubtitleColor.YELLOW) Color(0xFFFFEB3B) else Color.White
    val bg = when (st.background) {
        SubtitleBackground.NONE -> Color.Transparent
        SubtitleBackground.SEMI_TRANSPARENT -> Color.Black.copy(alpha = 0.6f)
        SubtitleBackground.OPAQUE -> Color.Black
    }
    val shadow = when (st.edge) {
        SubtitleEdge.NONE -> null
        SubtitleEdge.OUTLINE -> Shadow(Color.Black, blurRadius = 3f)
        SubtitleEdge.SHADOW -> Shadow(Color.Black, offset = androidx.compose.ui.geometry.Offset(3f, 3f), blurRadius = 6f)
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF5B4B8A), Color(0xFF1F6F78)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            stringResource(R.string.sub_preview_text),
            style = TextStyle(color = color, fontSize = (18 * st.scale).sp, shadow = shadow, textAlign = TextAlign.Center),
            modifier = Modifier.padding(bottom = (st.bottomMarginPercent * 2).dp).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------- Connection test

@Composable
fun ConnectionTestScreen(sourceId: Long, actions: AppActions) {
    val app = rememberApp()
    val context = LocalContext.current
    val test = app.core.connectionTest
    val state by test.state.collectAsStateWithLifecycle(ConnectionTestState.Idle)
    val settings by app.core.playerPreferences.settings.collectAsStateWithLifecycle(PlayerSettings())
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val start: () -> Unit = {
        if (!running) {
            running = true
            scope.launch {
                runCatching { test.run(sourceId) }
                running = false
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { test.reset() } }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.conn_test), onBack = actions::back)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (val s = state) {
                    ConnectionTestState.Idle -> {
                        Icon(Icons.Rounded.NetworkCheck, null, tint = Palette.Accent, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.conn_test_desc), color = Palette.TextSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton(stringResource(R.string.conn_test_start), onClick = start)
                    }
                    is ConnectionTestState.Running -> {
                        CircularProgressIndicator(color = Palette.Accent)
                        Spacer(Modifier.height(16.dp))
                        Text(s.step, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        ProgressBar(s.progress, Modifier.fillMaxWidth(), height = 6.dp)
                    }
                    is ConnectionTestState.Done -> {
                        val r = s.result
                        val good = r.verdict == ConnectionVerdict.GOOD
                        val color = if (good) Palette.Success else if (r.verdict == ConnectionVerdict.OFFLINE || r.verdict == ConnectionVerdict.PROVIDER_ERROR) Palette.Live else Color(0xFFFFB020)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f)).padding(20.dp),
                        ) {
                            Text(r.title, style = MaterialTheme.typography.headlineSmall, color = color)
                            Spacer(Modifier.height(8.dp))
                            Text(r.message, style = MaterialTheme.typography.bodyLarge)
                            r.testedChannelName?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.conn_test_channel, it), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Gauge(stringResource(R.string.conn_test_your_network), r.internetMbps, Modifier.weight(1f))
                            Gauge(stringResource(R.string.conn_test_provider), r.providerMbps, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        val extra = listOfNotNull(
                            r.providerFirstByteMs?.let { stringResource(R.string.conn_test_first_byte, it.toInt()) },
                            r.providerStability?.let { stringResource(R.string.conn_test_stability, (it * 100).toInt()) },
                        ).joinToString("   ·   ")
                        if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (r.recommendedBuffer != settings.bufferMode) {
                                val label = bufferLabel(r.recommendedBuffer)
                                PrimaryButton(stringResource(R.string.conn_test_apply, label), onClick = {
                                    scope.launch {
                                        app.core.playerPreferences.update { it.copy(bufferMode = r.recommendedBuffer) }
                                        context.toast(context.getString(R.string.conn_test_applied))
                                    }
                                })
                            }
                            SecondaryButton(stringResource(R.string.conn_test_again), onClick = start)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.conn_test_footer), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Gauge(label: String, mbps: Double?, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        Spacer(Modifier.height(10.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { ((mbps ?: 0.0) / 50.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.size(96.dp),
                color = Palette.Accent2,
                trackColor = Palette.SurfaceElevated,
                strokeWidth = 8.dp,
            )
            Text(mbps?.let { String.format(Locale.ITALY, "%.1f", it) } ?: "—", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(6.dp))
        Text("Mbps", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}
