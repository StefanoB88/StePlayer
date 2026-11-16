package com.steplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steplayer.app.R
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.Palette

private data class Component(val name: String, val license: String, val note: String? = null)

private val COMPONENTS = listOf(
    Component("AndroidX Media3 (ExoPlayer, decoder FFmpeg)", "Apache License 2.0"),
    Component("Jetpack Compose e librerie AndroidX", "Apache License 2.0"),
    Component("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0"),
    Component("Ktor", "Apache License 2.0"),
    Component("SQLDelight", "Apache License 2.0"),
    Component("OkHttp, Okio", "Apache License 2.0"),
    Component("Coil", "Apache License 2.0"),
    Component("Google Cast SDK", "Termini di servizio delle API Google"),
)

/** Open source notices; FFmpeg (LGPL) with its exact source revision and the full license text. */
@Composable
fun LicensesScreen(actions: AppActions) {
    val context = LocalContext.current
    val ffmpegInfo = remember { runCatching { context.assets.open("licenses/ffmpeg-build.txt").bufferedReader().readText().trim() }.getOrNull() }
    val lgpl = remember { runCatching { context.assets.open("licenses/COPYING.LGPLv2.1").bufferedReader().readText() }.getOrNull() }
    var showLgpl by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.settings_licenses), onBack = actions::back)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card {
                        Text("FFmpeg", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "GNU Lesser General Public License 2.1 o successiva. Usato come librerie dinamiche (libavcodec, " +
                                "libavutil, libswresample) per decodificare l'audio (AC-3, E-AC-3, DTS, TrueHD e altri). " +
                                "StePlayer non modifica FFmpeg; le librerie possono essere sostituite con altre versioni compatibili.",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                        )
                        if (ffmpegInfo != null) {
                            Text(ffmpegInfo, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = Palette.TextSecondary)
                        }
                        Text(
                            "Copia del codice sorgente: https://github.com/FFmpeg/FFmpeg (revisione indicata sopra), " +
                                "oppure su richiesta a stehelp@gmail.com.",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                        )
                        if (lgpl != null) {
                            val shape = RoundedCornerShape(10.dp)
                            Text(
                                if (showLgpl) "Nascondi il testo della licenza" else "Mostra il testo della licenza LGPL 2.1",
                                style = MaterialTheme.typography.labelLarge, color = Palette.Accent,
                                modifier = Modifier.focusHighlight(shape, 1.02f).clip(shape).clickable { showLgpl = !showLgpl }.padding(vertical = 8.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
                if (showLgpl && lgpl != null) {
                    item { Card { Text(lgpl, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = Palette.TextSecondary) } }
                }
                items(COMPONENTS) { c ->
                    Card {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(c.license, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}
