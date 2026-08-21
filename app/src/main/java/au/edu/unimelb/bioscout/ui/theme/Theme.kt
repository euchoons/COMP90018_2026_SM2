package au.edu.unimelb.bioscout.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColours = lightColorScheme(
    primary = Color(0xFF166534),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4DE),
    onPrimaryContainer = Color(0xFF062E16),
    secondary = Color(0xFF1F6F78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEEF1),
    onSecondaryContainer = Color(0xFF062F34),
    tertiary = Color(0xFF835500),
    tertiaryContainer = Color(0xFFFFDDA8),
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFFCFDF9),
    surfaceVariant = Color(0xFFE4EAE3),
    outline = Color(0xFF737A73),
    error = Color(0xFFBA1A1A),
)

private val DarkColours = darkColorScheme(
    primary = Color(0xFF9BDAA7),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF005227),
    onPrimaryContainer = Color(0xFFB7F7C1),
    secondary = Color(0xFF91D3DB),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF174E55),
    onSecondaryContainer = Color(0xFFADEFF7),
    background = Color(0xFF101410),
    surface = Color(0xFF101410),
    surfaceVariant = Color(0xFF414942),
)

@Composable
fun BioScoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        typography = MaterialTheme.typography,
        content = content,
    )
}
