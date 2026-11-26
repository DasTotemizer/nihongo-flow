package app.nihongoflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm paper neutrals with a restrained deep green (matcha / pine) accent.
private val Pine = Color(0xFF2F5D4A)
private val PineLight = Color(0xFF8DBBA5)
private val Paper = Color(0xFFFAF7F2)
private val PaperDeep = Color(0xFFF1ECE3)
private val Ink = Color(0xFF22231F)
private val InkSoft = Color(0xFF5E5F58)
private val Night = Color(0xFF161714)
private val NightRaised = Color(0xFF22241F)
private val Moon = Color(0xFFECE8DF)
private val Persimmon = Color(0xFFB86A3C) // warm secondary, never used for warnings

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E6DC),
    onPrimaryContainer = Color(0xFF0F2A1F),
    secondary = Persimmon,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = Color(0xFFCFC8BB),
)

private val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF0F2A1F),
    primaryContainer = Color(0xFF244636),
    onPrimaryContainer = Color(0xFFD5E6DC),
    secondary = Color(0xFFE0A27A),
    onSecondary = Color(0xFF3A1E0C),
    background = Night,
    onBackground = Moon,
    surface = Night,
    onSurface = Moon,
    surfaceVariant = NightRaised,
    onSurfaceVariant = Color(0xFFB9B5AB),
    outline = Color(0xFF45473F),
)

/**
 * Android's system font stack includes Noto Sans CJK, which renders kanji and kana well.
 * Text that is Japanese is marked with a Japanese locale (see JapaneseText) so the system
 * picks Japanese glyph shapes rather than Chinese ones for shared Han characters.
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun NihongoFlowTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
