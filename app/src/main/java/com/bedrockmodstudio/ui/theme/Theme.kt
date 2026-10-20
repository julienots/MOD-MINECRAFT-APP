package com.bedrockmodstudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object BmsColors {
    val Background = Color(0xFF111317)
    val Surface = Color(0xFF1A1D23)
    val SurfaceHigh = Color(0xFF232832)
    val Primary = Color(0xFF5FC27E)
    val OnPrimary = Color(0xFF0B2414)
    val Secondary = Color(0xFFFFD166)
    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFB454)
    val Info = Color(0xFF6CB6FF)
    val TextMuted = Color(0xFF9AA3AE)

    // Coloration syntaxique de l'éditeur.
    val JsonKey = Color(0xFF8FD3FF)
    val JsonString = Color(0xFFA5E07B)
    val JsonNumber = Color(0xFFFFB86C)
    val JsonKeyword = Color(0xFFFF79C6)
    val JsonComment = Color(0xFF6B7380)
    val JsonPunct = Color(0xFFC8CDD4)
}

private val scheme = darkColorScheme(
    primary = BmsColors.Primary,
    onPrimary = BmsColors.OnPrimary,
    primaryContainer = Color(0xFF1E3A28),
    onPrimaryContainer = Color(0xFFB8F0C8),
    secondary = BmsColors.Secondary,
    onSecondary = Color(0xFF2A2000),
    secondaryContainer = Color(0xFF3A3220),
    onSecondaryContainer = Color(0xFFFFE6A8),
    background = BmsColors.Background,
    onBackground = Color(0xFFE6E9ED),
    surface = BmsColors.Surface,
    onSurface = Color(0xFFE6E9ED),
    surfaceVariant = BmsColors.SurfaceHigh,
    onSurfaceVariant = BmsColors.TextMuted,
    surfaceContainer = BmsColors.Surface,
    surfaceContainerHigh = BmsColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2B313C),
    surfaceContainerLow = Color(0xFF15181D),
    error = BmsColors.Error,
    outline = Color(0xFF3A414D),
    outlineVariant = Color(0xFF2B313C),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun BmsTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
