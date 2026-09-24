package app.mangalens.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.mangalens.R
import kotlin.math.pow

/**
 * The POP! palette: cream newsprint, ink outlines and two loud colours.
 * Zap (yellow) always marks the next thing to do and Punch (red) marks what
 * is active or selected, so a reader learns the screen by colour alone.
 *
 * [faceInk] and [sleepBody] are the same in both themes on purpose: Fuki's
 * face is drawn on a yellow, red or cream body in either theme, and ink
 * that followed the theme would turn cream in the dark and vanish into the
 * yellow.
 */
@Immutable
internal data class PopColors(
    val paper: Color,
    val surface: Color,
    val surfaceHi: Color,
    val ink: Color,
    val inkSoft: Color,
    val punch: Color,
    val onPunch: Color,
    val punchText: Color,
    val zap: Color,
    val onZap: Color,
    val zapSoft: Color,
    val stroke: Color,
    val shadow: Color,
    /** Outlines each hard shadow in the dark, where black on near-black would not read as depth. */
    val shadowStroke: Color?,
    val dots: Color,
    val faceInk: Color,
    val sleepBody: Color,
    val dark: Boolean,
)

internal val LightPop = PopColors(
    paper = Color(0xFFFFF4DC),
    surface = Color(0xFFFFFBF0),
    surfaceHi = Color(0xFFF6E7C3),
    ink = Color(0xFF1C1424),
    inkSoft = Color(0xFF5A4B5E),
    punch = Color(0xFFD92B17),
    onPunch = Color(0xFFFFFFFF),
    punchText = Color(0xFFB81F0F),
    zap = Color(0xFFFFCC1A),
    onZap = Color(0xFF1C1424),
    zapSoft = Color(0xFFFFEFB0),
    stroke = Color(0xFF1C1424),
    shadow = Color(0xFF1C1424),
    shadowStroke = null,
    dots = Color(0xFFD92B17).copy(alpha = 0.16f),
    faceInk = Color(0xFF1C1424),
    sleepBody = Color(0xFFEFE4CC),
    dark = false,
)

internal val DarkPop = PopColors(
    paper = Color(0xFF15101A),
    surface = Color(0xFF221A29),
    surfaceHi = Color(0xFF2E2436),
    ink = Color(0xFFFFF4DC),
    inkSoft = Color(0xFFCDBFCF),
    punch = Color(0xFFFF5A43),
    onPunch = Color(0xFF1C1424),
    punchText = Color(0xFFFF8A77),
    zap = Color(0xFFFFD233),
    onZap = Color(0xFF1C1424),
    zapSoft = Color(0xFF3A3014),
    stroke = Color(0xFFF2E6CC),
    shadow = Color(0xFF000000),
    shadowStroke = Color(0xFFF2E6CC).copy(alpha = 0.45f),
    dots = Color(0xFFFF5A43).copy(alpha = 0.22f),
    faceInk = Color(0xFF1C1424),
    sleepBody = Color(0xFFEFE4CC),
    dark = true,
)

internal val LocalPop = staticCompositionLocalOf { LightPop }

/**
 * True when the system's "Remove animations" is on. Compose already scales
 * finite tweens by the animator scale; this gate covers what it cannot:
 * infinite loops, keyframes and anything timed by a delay.
 */
internal val LocalReducedMotion = staticCompositionLocalOf { false }

internal val ComicNeue = FontFamily(
    Font(R.font.comic_neue_regular, FontWeight.Normal),
    Font(R.font.comic_neue_bold, FontWeight.Bold),
    Font(R.font.comic_neue_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.comic_neue_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

private fun comic(size: Int, line: Int, bold: Boolean, spacing: Float = 0f) = TextStyle(
    fontFamily = ComicNeue,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

/**
 * Comic Neue everywhere, bold for anything a reader acts on. Nothing is
 * smaller than 14sp: the smallest slots repeat [Typography.bodyMedium]
 * and [Typography.labelMedium] so no component falls back to a size the
 * rest of the app never uses.
 */
internal val PopTypography = Typography(
    displayLarge = comic(48, 52, bold = true, spacing = -0.5f),
    displayMedium = comic(44, 48, bold = true, spacing = -0.5f),
    displaySmall = comic(40, 44, bold = true, spacing = -0.5f),
    headlineLarge = comic(40, 44, bold = true, spacing = -0.5f),
    headlineMedium = comic(32, 36, bold = true),
    headlineSmall = comic(28, 32, bold = true),
    titleLarge = comic(22, 28, bold = true),
    titleMedium = comic(18, 22, bold = true),
    titleSmall = comic(16, 20, bold = true),
    bodyLarge = comic(16, 22, bold = false),
    bodyMedium = comic(14, 20, bold = false),
    bodySmall = comic(14, 20, bold = false),
    labelLarge = comic(18, 22, bold = true),
    labelMedium = comic(14, 18, bold = true, spacing = 0.2f),
    labelSmall = comic(14, 18, bold = true, spacing = 0.2f),
)

/** Secrets, model ids and URLs: a monospace face makes a pasted key easy to check at a glance. */
internal val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 20.sp)

private fun schemeFor(p: PopColors) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.punch,
    onPrimary = p.onPunch,
    primaryContainer = p.zapSoft,
    onPrimaryContainer = p.ink,
    secondary = p.zap,
    onSecondary = p.onZap,
    secondaryContainer = p.zapSoft,
    onSecondaryContainer = p.ink,
    tertiary = p.zap,
    onTertiary = p.onZap,
    background = p.paper,
    onBackground = p.ink,
    surface = p.surface,
    onSurface = p.ink,
    surfaceVariant = p.surfaceHi,
    onSurfaceVariant = p.inkSoft,
    surfaceContainerLowest = p.surface,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.surface,
    surfaceContainerHighest = p.surface,
    surfaceBright = p.surface,
    surfaceDim = p.surface,
    surfaceTint = Color.Transparent,
    inverseSurface = p.ink,
    inverseOnSurface = p.paper,
    outline = p.stroke,
    outlineVariant = p.inkSoft,
    error = p.punchText,
    onError = p.paper,
    scrim = Color.Black.copy(alpha = 0.45f),
)

/**
 * The app theme. There is deliberately no dynamic (wallpaper) colour:
 * MangaLens's identity is its fixed yellow-on-red, and wallpaper colours
 * would fight it and break the contrast pairs the palette was checked for.
 */
@Composable
fun MangaLensTheme(content: @Composable () -> Unit) {
    val pop = if (isSystemInDarkTheme()) DarkPop else LightPop
    CompositionLocalProvider(LocalPop provides pop) {
        MaterialTheme(colorScheme = schemeFor(pop), typography = PopTypography, content = content)
    }
}

/**
 * The WCAG 2 contrast ratio of two opaque ARGB colours, from 1 (identical)
 * to 21 (black on white). Used by tests that hold the palette to its
 * contrast pairs; alpha is ignored, so composite first.
 */
internal fun contrastRatio(a: Int, b: Int): Double {
    fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
