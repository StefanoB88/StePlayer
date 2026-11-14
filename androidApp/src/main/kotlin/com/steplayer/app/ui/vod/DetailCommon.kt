package com.steplayer.app.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.steplayer.app.R
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.theme.Palette
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus

@Composable
fun BackdropHeader(image: String?, title: String, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height)) {
        ArtImage(image, title, Modifier.fillMaxSize(), placeholderTextVisible = false)
        Box(Modifier.matchParentSize().background(Palette.ScrimBottom))
    }
}

/** Height of [DetailTopBar] below the status bar. */
val DetailTopBarHeight = 64.dp

/**
 * Tracks the page title of a detail page: [DetailCollapse.collapsed] turns true as soon as the
 * title reaches the bottom of the top bar — i.e. the backdrop it sits on has scrolled away and
 * the title would otherwise pass under the floating back arrow. Put [DetailCollapse.titleModifier]
 * on the title Text. Position is read in the layout phase; only the threshold crossing recomposes.
 */
class DetailCollapse internal constructor(val titleModifier: Modifier, val collapsed: State<Boolean>)

@Composable
fun rememberDetailCollapse(forceCollapsed: () -> Boolean = { false }): DetailCollapse {
    val density = LocalDensity.current
    val barBottom = WindowInsets.statusBars.getTop(density) + with(density) { DetailTopBarHeight.toPx() }
    val titleTop = remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    val collapsed = remember(barBottom) { derivedStateOf { forceCollapsed() || titleTop.floatValue < barBottom } }
    val modifier = remember { Modifier.onGloballyPositioned { titleTop.floatValue = it.positionInRoot().y } }
    return remember(collapsed) { DetailCollapse(modifier, collapsed) }
}

/** Translucent dark circle behind icons floating over artwork. */
val FloatingIconScrim = Color.Black.copy(alpha = 0.45f)

/**
 * Top bar of the movie/series detail pages. While the backdrop is visible only the back arrow
 * floats over it (on a dark translucent circle, readable over any image); once the backdrop has
 * scrolled away ([collapsed]) a solid bar with the title fades in (150 ms), so the arrow never
 * overlaps the page title.
 */
@Composable
fun DetailTopBar(
    title: String,
    collapsed: State<Boolean>,
    onBack: () -> Unit,
    actions: @Composable RowScope.(scrim: Color) -> Unit = {},
) {
    val solid by animateFloatAsState(if (collapsed.value) 1f else 0f, tween(150), label = "detailBar")
    // Deferred reads (draw phase): scrolling doesn't recompose the bar, only the fade does.
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = solid }.background(Palette.Background))
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(DetailTopBarHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val scrim = FloatingIconScrim.copy(alpha = FloatingIconScrim.alpha * (1f - solid))
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                RoundIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack,
                    size = 40.dp, background = scrim,
                )
            }
            if (solid > 0f) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).graphicsLayer { alpha = solid },
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            actions(scrim)
        }
    }
}

/** Wraps a floating icon (e.g. the cast button) in the same circular scrim as the back arrow. */
@Composable
fun ScrimmedIcon(scrim: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(scrim), contentAlignment = Alignment.Center) { content() }
}

fun downloadIcon(d: Download?): ImageVector = when (d?.status) {
    null -> Icons.Rounded.Download
    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> Icons.Rounded.Downloading
    DownloadStatus.PAUSED -> Icons.Rounded.PauseCircle
    DownloadStatus.COMPLETED -> Icons.Rounded.CheckCircle
    DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline
}

@Composable
fun downloadLabel(d: Download?): String = when (d?.status) {
    null -> stringResource(R.string.download)
    DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
    DownloadStatus.RUNNING -> stringResource(R.string.downloading, (d.fraction * 100).toInt())
    DownloadStatus.PAUSED -> stringResource(R.string.status_paused)
    DownloadStatus.COMPLETED -> stringResource(R.string.downloaded)
    DownloadStatus.FAILED -> stringResource(R.string.retry)
}
