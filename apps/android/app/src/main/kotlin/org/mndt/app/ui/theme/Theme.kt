package org.mndt.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Figma "Design Tokens" (7:206). Dark only: the design has no light mode. */
object Mosimo {
    val bgBase = Color(0xFF0B0D10)
    val bgSurface = Color(0xFF15181D)
    val bgSurface2 = Color(0xFF1E2229)
    val bgElevated = Color(0xFF262B33)
    val border = Color(0xFF2A2F36)
    val borderStrong = Color(0xFF3A4049)
    val textPrimary = Color(0xFFF2F4F7)
    val textSecondary = Color(0xFF9AA3AD)
    val textTertiary = Color(0xFF6B7480)
    val onPrimary = Color(0xFF1A1200)
    /** Primary CTA, selected chip, inundation only; never on persistent chrome such as headers. */
    val primary = Color(0xFFFFB900)
    val danger = Color(0xFFFF3B30)
    val warning = Color(0xFFFF8A00)
    /** Safe, shelters, verified signatures. */
    val safe = Color(0xFF34C759)
    /** Communication: mesh, BLE, own position, relayed. */
    val info = Color(0xFF4DA3FF)

    /** JetBrains Mono Bold 18 in Figma: counts, times, heights. */
    val number = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
}

private val colors = darkColorScheme(
    primary = Mosimo.primary, onPrimary = Mosimo.onPrimary,
    background = Mosimo.bgBase, onBackground = Mosimo.textPrimary,
    surface = Mosimo.bgSurface, onSurface = Mosimo.textPrimary,
    surfaceVariant = Mosimo.bgSurface2, onSurfaceVariant = Mosimo.textSecondary,
    surfaceContainerLowest = Mosimo.bgBase, surfaceContainerLow = Mosimo.bgSurface, surfaceContainer = Mosimo.bgSurface,
    surfaceContainerHigh = Mosimo.bgSurface2, surfaceContainerHighest = Mosimo.bgElevated,
    outline = Mosimo.borderStrong, outlineVariant = Mosimo.border,
    error = Mosimo.danger, onError = Mosimo.textPrimary,
    secondary = Mosimo.info, tertiary = Mosimo.safe,
)

// System sans is Noto Sans CJK JP on Android, so the Noto Sans JP sizes apply without bundling fonts.
private val typography = Typography().run {
    copy(
        headlineMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 40.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    )
}

@Composable
fun MosimoTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = typography, content = content)
