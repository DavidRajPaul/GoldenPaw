package com.goldenpaw.ui.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm palette: honey, sage, soft cream; deep warm greys for dark (never pure black).
object GpColors {
    val Honey = Color(0xFFC8862A)
    val HoneyLight = Color(0xFFF0B85C)
    val HoneyContainer = Color(0xFFFCE3B6)
    val Sage = Color(0xFF6F8F6A)
    val SageLight = Color(0xFFA9C9A2)
    val SageContainer = Color(0xFFD7E8CF)
    val Clay = Color(0xFFB5654A)
    val ClayLight = Color(0xFFFFB59D)
    val ClayContainer = Color(0xFFFFDBCF)
    val Cream = Color(0xFFFFF8EE)
    val Night = Color(0xFF1C1A17)
}

/** Semantic colors for wellness states that Material's scheme doesn't cover. */
@Immutable
data class WellnessColors(
    val good: Color,
    val okay: Color,
    val hard: Color,
    val empty: Color,
    val onGood: Color,
)

val LocalWellnessColors = staticCompositionLocalOf {
    WellnessColors(GpColors.Sage, GpColors.Honey, GpColors.Clay, Color(0xFFEDE3D3), Color.White)
}

/** When true, animations are swapped for simple fades / instant changes. */
val LocalReduceMotion = staticCompositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = GpColors.Honey,
    onPrimary = Color.White,
    primaryContainer = GpColors.HoneyContainer,
    onPrimaryContainer = Color(0xFF3D2600),
    secondary = GpColors.Sage,
    onSecondary = Color.White,
    secondaryContainer = GpColors.SageContainer,
    onSecondaryContainer = Color(0xFF1E3420),
    tertiary = GpColors.Clay,
    onTertiary = Color.White,
    tertiaryContainer = GpColors.ClayContainer,
    onTertiaryContainer = Color(0xFF3B0C00),
    background = GpColors.Cream,
    onBackground = Color(0xFF221B12),
    surface = GpColors.Cream,
    onSurface = Color(0xFF221B12),
    surfaceVariant = Color(0xFFF1E6D6),
    onSurfaceVariant = Color(0xFF52463A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF3E3),
    surfaceContainer = Color(0xFFFBEEDB),
    surfaceContainerHigh = Color(0xFFF6E8D3),
    surfaceContainerHighest = Color(0xFFF0E2CC),
    outline = Color(0xFF857565),
    outlineVariant = Color(0xFFD8C7B3),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = GpColors.HoneyLight,
    onPrimary = Color(0xFF452B00),
    primaryContainer = Color(0xFF633F00),
    onPrimaryContainer = GpColors.HoneyContainer,
    secondary = GpColors.SageLight,
    onSecondary = Color(0xFF173519),
    secondaryContainer = Color(0xFF2F4B2E),
    onSecondaryContainer = GpColors.SageContainer,
    tertiary = GpColors.ClayLight,
    onTertiary = Color(0xFF5C1A06),
    tertiaryContainer = Color(0xFF7A3019),
    onTertiaryContainer = GpColors.ClayContainer,
    background = GpColors.Night,
    onBackground = Color(0xFFEDE1D3),
    surface = GpColors.Night,
    onSurface = Color(0xFFEDE1D3),
    surfaceVariant = Color(0xFF4F453A),
    onSurfaceVariant = Color(0xFFD5C4B2),
    surfaceContainerLowest = Color(0xFF161411),
    surfaceContainerLow = Color(0xFF221F1B),
    surfaceContainer = Color(0xFF26231F),
    surfaceContainerHigh = Color(0xFF312D28),
    surfaceContainerHighest = Color(0xFF3C3832),
    outline = Color(0xFF9E8F7F),
    outlineVariant = Color(0xFF4F453A),
)

private val Serif = FontFamily.Serif

private val GpTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

private val GpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun GoldenPawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val wellness = if (darkTheme) {
        WellnessColors(GpColors.SageLight, GpColors.HoneyLight, GpColors.ClayLight, Color(0xFF3A342D), Color(0xFF14240F))
    } else {
        WellnessColors(GpColors.Sage, GpColors.Honey, GpColors.Clay, Color(0xFFEDE3D3), Color.White)
    }
    CompositionLocalProvider(
        LocalWellnessColors provides wellness,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = GpTypography, shapes = GpShapes, content = content)
    }
}

val SectionLabelStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp)
