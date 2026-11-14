package com.steplayer.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF141821)
    val SurfaceElevated = Color(0xFF1C2130)
    val Outline = Color(0xFF2A3142)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFF9AA3B2)
    val Accent = Color(0xFF7C5CFF)
    val Accent2 = Color(0xFF00D4FF)
    val Live = Color(0xFFFF3B5C)
    val Success = Color(0xFF2ED47A)

    val AccentGradient = Brush.linearGradient(listOf(Accent, Accent2))
    val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Background.copy(alpha = 0.85f), Background))
    val ScrimLeft = Brush.horizontalGradient(listOf(Background, Background.copy(alpha = 0.6f), Color.Transparent))
}

object Dimens {
    val CardRadius = 16.dp
    val PosterRadius = 12.dp
    val Gutter = 16.dp
    val Section = 24.dp
}

private val ColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.Accent2,
    onSecondary = Color.Black,
    tertiary = Palette.Accent2,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceElevated,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceElevated,
    surfaceContainerHighest = Palette.SurfaceElevated,
    surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Background,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Live,
)

private val Sans = FontFamily.SansSerif

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(Dimens.PosterRadius),
    large = RoundedCornerShape(Dimens.CardRadius),
    extraLarge = RoundedCornerShape(24.dp),
)

/** True when running on Android TV / Fire TV. */
val LocalIsTv = staticCompositionLocalOf { false }

@Composable
fun StePlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = AppTypography, shapes = AppShapes) {
        // Screens draw on plain Boxes (not Surfaces): give text/icons the light default colour.
        CompositionLocalProvider(LocalContentColor provides Palette.TextPrimary, content = content)
    }
}
