package au.edu.unimelb.floraguide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColours = lightColorScheme(
    primary = Color(0xFF242424),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF242424),
    secondary = Color.White,
    onSecondary = Color(0xFF242424),
    secondaryContainer = Color(0xFFE2E2E2),
    onSecondaryContainer = Color(0xFF242424),
    background = Color(0xFFE2E2E2),
    surface = Color(0xFFFCFDF9),
    surfaceVariant = Color(0x405ee9b5),
    onSurfaceVariant = Color(0xff5ee9b5),
    outline = Color(0xFFD4D4D4),
    error = Color(0xffff2056),
)

private val DarkColours = darkColorScheme(
    primary = Color(0xFFD4D4D4),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF181818),
    onPrimaryContainer = Color(0xFFAAAAAA),
    secondary = Color.Black,
    onSecondary = Color(0xFFAAAAAA),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFAAAAAA),
    background = Color(0xFF0C0C0C),
    surface = Color(0xFF181818),
    surfaceVariant = Color(0x405ee9b5),
    onSurfaceVariant = Color(0xff5ee9b5),
    outline = Color(0xFF242424),
)

@Composable
fun FloraGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        typography = MaterialTheme.typography,
        content = content,
    )
}
