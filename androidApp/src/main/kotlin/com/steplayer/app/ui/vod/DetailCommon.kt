package com.steplayer.app.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import com.steplayer.app.ui.components.tvFocusStop
import com.steplayer.app.ui.theme.LocalIsTv
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

/**
 * Compact detail layout for wide but short screens: TV (960x540 dp at 1080p/xhdpi and 720p/tvdpi),
 * phones in landscape and tablets in landscape (~1280x750 dp usable on a 10-11" tablet). The tall
 * hero layout put the action buttons ~550 dp down the page, i.e. below the bottom edge of a TV, and
 * on a landscape tablet it pushed the season chips and the episode list off the first screen.
 * Tablets in portrait (800x1280 dp) keep the hero layout: there the height is plenty.
 */
@Composable
fun rememberDetailCompact(): Boolean {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp
    val h = cfg.screenHeightDp
    return LocalIsTv.current || (w >= 600 && w > h && (h < 900 || w >= h * 1.4f))
}

/** Side margin of the compact detail page: wider on TV, clear of the overscan area. */
@Composable
private fun compactGutter(): Dp = if (LocalIsTv.current) 48.dp else 24.dp

/**
 * Widest the text column of the compact header gets: on a landscape tablet a plot running the full
 * ~1100 dp next to the poster is hard to read. TV (≤ 690 dp available) never reaches it.
 */
val CompactDetailMaxTextWidth = 760.dp

/** Backdrop filling the whole screen behind a compact detail page (it stays put while the page scrolls). */
@Composable
fun DetailBackdropBackground(image: String?, title: String) {
    Box(Modifier.fillMaxSize()) {
        ArtImage(image, title, Modifier.fillMaxSize(), placeholderTextVisible = false)
        Box(Modifier.matchParentSize().background(Palette.Background.copy(alpha = 0.35f)))
        Box(Modifier.matchParentSize().background(Palette.ScrimLeft))
        Box(Modifier.matchParentSize().background(Palette.ScrimBottom))
    }
}

/**
 * Poster on the left, [content] (title, meta, buttons, plot) on the right, starting right under the
 * top bar: on a 540 dp tall screen the buttons are always on the first screen.
 */
@Composable
fun CompactDetailHeader(poster: String?, title: String, content: @Composable ColumnScope.() -> Unit) {
    val isTv = LocalIsTv.current
    // Landscape tablets (≥ 700 dp tall) have room for a bigger poster; phones in landscape keep 120 dp.
    val posterWidth = when {
        isTv -> 150.dp
        LocalConfiguration.current.screenHeightDp >= 700 -> 170.dp
        else -> 120.dp
    }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = DetailTopBarHeight + 8.dp, start = compactGutter(), end = compactGutter()),
        verticalAlignment = Alignment.Top,
    ) {
        ArtImage(poster, title, Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f, fill = false).widthIn(max = CompactDetailMaxTextWidth), content = content)
    }
}

/**
 * TV: makes a block of text (plot, cast…) a focus stop of its own, so the D-pad can reach it and the
 * page scrolls to it even when no button follows. No-op on touch screens.
 */
@Composable
fun Modifier.tvReadable(): Modifier {
    if (!LocalIsTv.current) return this
    // Negative offset + padding: the text stays aligned with the rest, the focus border gets some air.
    return offset(x = (-10).dp).tvFocusStop().padding(horizontal = 10.dp, vertical = 6.dp)
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
    // 1 px tolerance: a title laid out right at the bar's bottom edge (dp rounding at tvdpi) is not under it.
    val collapsed = remember(barBottom) { derivedStateOf { forceCollapsed() || titleTop.floatValue < barBottom - 1f } }
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
