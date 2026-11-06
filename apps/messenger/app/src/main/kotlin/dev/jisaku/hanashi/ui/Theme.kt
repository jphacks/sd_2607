package dev.jisaku.hanashi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Hanashi {
    val green = Color(0xFF06C755)
    val myBubble = Color(0xFF8DE055)
    val chatBackground = Color(0xFF8CABD9)
    val muted = Color(0xFF8A8F98)
}

// ponytail: light theme only; add a dark scheme if the demo needs it.
@Composable
fun HanashiTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(primary = Hanashi.green, onPrimary = Color.White, secondary = Hanashi.green,
        secondaryContainer = Color(0xFFDDF6E6),
        background = Color.White, surface = Color.White, surfaceContainer = Color(0xFFF7F8FA)),
    content = content,
)

private val palette = listOf(0xFFEF8D8D, 0xFFF2B36B, 0xFF7FC97F, 0xFF6FB7E0, 0xFFA48BE0, 0xFFE38BC4, 0xFF5CC3B8).map { Color(it) }

@Composable
fun Avatar(pubkey: String, name: String, size: Dp = 48.dp) = Box(
    Modifier.size(size).clip(CircleShape).background(palette[Math.floorMod(pubkey.hashCode(), palette.size)]),
    contentAlignment = Alignment.Center,
) { Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp) }

private val zone get() = ZoneId.systemDefault()
fun clock(seconds: Long): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(seconds).atZone(zone))
fun day(seconds: Long): LocalDate = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()
fun dayLabel(date: LocalDate): String = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN).format(date)
/** Chat list style: time today, otherwise the date. */
fun shortTime(seconds: Long): String =
    if (day(seconds) == LocalDate.now(zone)) clock(seconds) else DateTimeFormatter.ofPattern("M/d").format(day(seconds))
