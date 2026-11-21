package lingue.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Compose draws its own pixels through Skia rather than asking the desktop
 * toolkit, so the palette below looks the same regardless of how old the host
 * system's GTK theme is.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF004A7F),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBAC8DB),
    onSecondary = Color(0xFF253141),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF1D2126),
    onSurfaceVariant = Color(0xFFC3C7CF),
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFE2E2E6),
    outline = Color(0xFF8D9199),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D33),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFF1F3F7),
    onSurfaceVariant = Color(0xFF42474E),
    background = Color(0xFFFAFAFD),
    onBackground = Color(0xFF1A1C1E),
    outline = Color(0xFF72777F),
)

/** Semantic colours for answer feedback, kept out of the Material roles. */
object Verdict {
    val Correct = Color(0xFF62D89B)
    val CorrectContainer = Color(0xFF123528)
    val Wrong = Color(0xFFFF9C90)
    val WrongContainer = Color(0xFF3A1B1B)
}

private val AppTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun LingueTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
