package au.edu.unimelb.floraguide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val LightColours = lightColorScheme(
    primary = Color(0xFF242424),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF242424),
    secondary = Color.White,
    onSecondary = Color(0xFF242424),
    secondaryContainer = Color(0x80E2E2E2),
    onSecondaryContainer = Color(0xFF303030),
    background = Color(0xFFE2E2E2),
    surface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0x4026B887),
    onSurfaceVariant = Color(0xFF0D513C),
    outline = Color(0xFF767676),
    error = Color(0xFFB0003A),
)

internal val DarkColours = darkColorScheme(
    primary = Color(0xFFD4D4D4),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF242424),
    onPrimaryContainer = Color(0xFFD4D4D4),
    secondary = Color.Black,
    onSecondary = Color(0xFFAAAAAA),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFAAAAAA),
    background = Color(0xFF0C0C0C),
    surface = Color(0xFF242424),
    surfaceVariant = Color(0x4026B887),
    onSurfaceVariant = Color(0xFFB5FFDF),
    outline = Color(0xFF8C8C8C),
)

@Composable
fun FloraGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        typography = AppTypography,
        content = content,
    )
}
