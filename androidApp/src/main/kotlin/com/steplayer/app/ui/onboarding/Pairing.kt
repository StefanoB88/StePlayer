package com.steplayer.app.ui.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WifiOff
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.steplayer.app.R
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.rememberApp
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import com.steplayer.shared.pairing.PairingState
import kotlinx.coroutines.delay

/**
 * "Usa il telefono": the TV runs a tiny LAN-only form server (shared core); the phone scans the QR,
 * fills in the list and sends it. Nothing goes through the internet or third parties.
 */
@Composable
fun PairingPanel(onAdded: (Long) -> Unit, modifier: Modifier = Modifier) {
    val app = rememberApp()
    val pairing = app.core.pairing
    val state by pairing.state.collectAsStateWithLifecycle(PairingState.Idle)
    var attempt by remember { mutableIntStateOf(0) }
    var offline by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) { onDispose { pairing.stop() } }
    LaunchedEffect(attempt) {
        offline = pairing.start() == null
    }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(state) {
        (state as? PairingState.Added)?.let { onAdded(it.sourceId) }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            is PairingState.Waiting -> {
                val expired = now >= s.expiresAtMs
                if (expired) {
                    Text(stringResource(R.string.pair_expired), color = Palette.TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(stringResource(R.string.pair_new_code), onClick = { attempt++ })
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrCode(s.url, Modifier.size(320.dp))
                        Column(Modifier.width(360.dp)) {
                            Text(stringResource(R.string.pair_step1), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.pair_step2), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.pair_step3), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(24.dp))
                            Text(s.url, style = MaterialTheme.typography.titleMedium, color = Palette.Accent2)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.pair_expires_in, Format.duration(s.expiresAtMs - now)),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            )
                        }
                    }
                }
            }
            PairingState.Verifying -> {
                CircularProgressIndicator(color = Palette.Accent)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pair_verifying), color = Palette.TextSecondary)
            }
            is PairingState.Failed -> {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Live, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(s.message, color = Palette.TextPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.pair_new_code), onClick = { attempt++ })
            }
            is PairingState.Added -> CircularProgressIndicator(color = Palette.Accent)
            PairingState.Idle -> if (offline) {
                Icon(Icons.Rounded.WifiOff, null, tint = Palette.TextSecondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.pair_offline), color = Palette.TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.pair_new_code), onClick = { attempt++ })
            } else {
                CircularProgressIndicator(color = Palette.Accent)
            }
        }
    }
}

/** White modules on the app background, with a quiet zone. */
@Composable
private fun QrCode(content: String, modifier: Modifier) {
    val bitmap = remember(content) { renderQr(content, 640) }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Background).padding(8.dp)) {
        Image(bitmap.asImageBitmap(), contentDescription = content, modifier = Modifier.fillMaxWidth())
    }
}

private fun renderQr(content: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val on = android.graphics.Color.WHITE
    val off = Palette.Background.toArgb()
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) on else off }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
