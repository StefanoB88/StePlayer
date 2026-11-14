package com.steplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.steplayer.app.R
import com.steplayer.app.ui.theme.Dimens
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette

/**
 * Network artwork with a tasteful placeholder (initials on a gradient) and no layout jank.
 *
 * Plain [AsyncImage] (no subcomposition per item, which made fast list scrolling expensive): the
 * request is sized from the layout constraints, so a 2:3 poster or a channel logo is decoded at the
 * size it is shown, never at full resolution. While loading only the surface colour shows; on error
 * the initials placeholder is drawn underneath the (empty) image.
 */
@Composable
fun ArtImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderTextVisible: Boolean = true,
) {
    Box(modifier.background(Palette.SurfaceElevated)) {
        if (url.isNullOrBlank()) {
            Placeholder(title, placeholderTextVisible)
        } else {
            var failed by remember(url) { mutableStateOf(false) }
            if (failed) Placeholder(title, placeholderTextVisible)
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { failed = it is AsyncImagePainter.State.Error },
            )
        }
    }
}

@Composable
private fun BoxScope.Placeholder(title: String, showText: Boolean) {
    Box(
        Modifier
            .matchParentSize()
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.SurfaceElevated, Palette.Surface))),
        contentAlignment = Alignment.Center,
    ) {
        if (showText) {
            Text(
                text = initials(title),
                style = MaterialTheme.typography.titleLarge,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun initials(title: String): String =
    title.split(' ', '-', '_').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "•" }

private val PosterShape = RoundedCornerShape(Dimens.PosterRadius)
private val RatingShape = RoundedCornerShape(6.dp)
private val RatingBackground = Color.Black.copy(alpha = 0.6f)
private val RatingStar = Color(0xFFFFC542)

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rating: String? = null,
    progress: Float? = null,
    width: Dp? = null,
    onClick: () -> Unit,
) {
    val shape = PosterShape
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusHighlight(shape)
                .clip(shape)
                .clickable(onClick = onClick),
        ) {
            ArtImage(imageUrl, title, Modifier.fillMaxSize())
            if (rating != null) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(RatingBackground, RatingShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, null, tint = RatingStar, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(rating, style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            if (progress != null && progress > 0f) {
                ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1)
        }
    }
}

private val CardScrim = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))

/** 16:9 card used for "continue watching" and channels on the home screen. */
@Composable
fun WideCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    live: Boolean = false,
    fitImage: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.CardRadius)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusHighlight(shape)
                .clip(shape)
                .clickable(onClick = onClick),
        ) {
            ArtImage(
                imageUrl, title,
                Modifier.fillMaxSize().then(if (fitImage) Modifier.padding(20.dp) else Modifier),
                contentScale = if (fitImage) ContentScale.Fit else ContentScale.Crop,
            )
            Box(Modifier.matchParentSize().background(CardScrim))
            if (live) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            if (progress != null && progress > 0f) {
                ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = Palette.Accent) =
    ProgressBar({ progress }, modifier, height, color)

/** [progress] is read at draw time: time-based progress (EPG) updates without recomposition. */
@Composable
fun ProgressBar(progress: () -> Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = Palette.Accent) {
    LinearProgressIndicator(
        progress = { progress().coerceIn(0f, 1f) },
        modifier = modifier.height(height).clip(CircleShape),
        color = color,
        trackColor = Color.White.copy(alpha = 0.18f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Palette.Live, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(stringResource(R.string.live), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Immutable
data class ChipItem(val id: String, val label: String)

/** Horizontally scrolling, fully rounded category chips; selected chip uses the accent gradient. */
@Composable
fun CategoryChips(
    items: List<ChipItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selectedId, items.size) {
        val idx = items.indexOfFirst { it.id == selectedId }
        if (idx >= 0 && idx !in state.layoutInfo.visibleItemsInfo.map { it.index }) state.scrollToItem(idx)
    }
    LazyRow(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Dimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }, contentType = { "chip" }) { item ->
            val selected = item.id == selectedId
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .focusHighlight(shape, 1.04f)
                    .clip(shape)
                    .then(if (selected) Modifier.background(Palette.AccentGradient) else Modifier.background(Palette.SurfaceElevated))
                    .clickable { onSelect(item.id) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else Palette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp),
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .focusHighlight(shape, 1.04f)
            .clip(shape)
            .background(if (enabled) Palette.AccentGradient else androidx.compose.ui.graphics.SolidColor(Palette.Outline))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Palette.TextPrimary,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .focusHighlight(shape, 1.04f)
            .clip(shape)
            .background(Palette.SurfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) tint else Palette.TextSecondary, modifier = Modifier.size(20.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        }
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Palette.TextPrimary else Palette.TextSecondary)
        }
    }
}

/** Round icon button with visible D-pad focus. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = Color.Transparent,
    tint: Color = Palette.TextPrimary,
) {
    Box(
        modifier
            .size(size)
            .focusHighlight(CircleShape, 1.1f)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).background(Palette.SurfaceElevated, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp)
    }
}

/** Long-pressable list row used by the channel list. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Grid min cell size adapted to the device class. */
@Composable
fun posterMinSize(): Dp = if (LocalIsTv.current) 150.dp else 112.dp
