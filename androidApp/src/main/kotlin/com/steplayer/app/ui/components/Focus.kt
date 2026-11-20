package com.steplayer.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.steplayer.app.ui.theme.Dimens
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette

/**
 * Visible focus for D-pad navigation: scale + accent gradient border.
 * Place it BEFORE clickable/focusable in the modifier chain.
 */
@Composable
fun Modifier.focusHighlight(
    shape: Shape = RoundedCornerShape(Dimens.CardRadius),
    scale: Float = 1.06f,
    /** false = keep tracking focus but draw nothing (e.g. while a button shows a spinner). */
    enabled: Boolean = true,
): Modifier {
    var hasFocus by remember { mutableStateOf(false) }
    val focused = hasFocus && enabled
    val s by animateFloatAsState(if (focused) scale else 1f, tween(180), label = "focusScale")
    return this
        .onFocusChanged { hasFocus = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        // Border node only while focused: thousands of list rows don't each carry an idle border.
        .then(if (focused) Modifier.border(2.5.dp, Palette.AccentGradient, shape) else Modifier)
}

/**
 * TV: makes a non-interactive block (a paragraph, a card) a D-pad focus stop, so a scrolling list
 * can be read to the end with the remote even where there is nothing to click. No-op on touch screens.
 */
@Composable
fun Modifier.tvFocusStop(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    if (!LocalIsTv.current) this else focusHighlight(shape, 1f).focusable()
