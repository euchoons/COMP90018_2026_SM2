package au.edu.unimelb.nightwatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Safe = Color(0xFF4FE0A6)
val Caution = Color(0xFFFFB454)
val Alarm = Color(0xFFFF5C70)
val NightSurface = Color(0xFF111A31)
val NightBackground = Color(0xFF0A1020)

private val DarkColors = darkColorScheme(
    primary = Safe,
    onPrimary = Color(0xFF05231A),
    error = Alarm,
    background = NightBackground,
    surface = NightSurface,
    onSurface = Color(0xFFEAEEF8),
    onSurfaceVariant = Color(0xFF8A97B8)
)

private val LightColors = lightColorScheme(primary = Color(0xFF00695C), error = Alarm)

@Composable
fun NightwatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Defaults to the dark scheme: the app is used at night and a bright screen
    // ruins the user's night vision.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
