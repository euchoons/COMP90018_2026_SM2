package au.edu.unimelb.floraguide.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import au.edu.unimelb.floraguide.R

// Bundle Inter locally so the app's typography also works offline.
@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    (100..900 step 100).map { weight ->
        Font(
            resId = R.font.inter,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

private val DefaultTypography = Typography()

val AppTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(fontFamily = Inter),
    displayMedium = DefaultTypography.displayMedium.copy(fontFamily = Inter),
    displaySmall = DefaultTypography.displaySmall.copy(fontFamily = Inter),
    headlineLarge = DefaultTypography.headlineLarge.copy(fontFamily = Inter),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontFamily = Inter),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontFamily = Inter),
    titleLarge = DefaultTypography.titleLarge.copy(fontFamily = Inter),
    titleMedium = DefaultTypography.titleMedium.copy(fontFamily = Inter),
    titleSmall = DefaultTypography.titleSmall.copy(fontFamily = Inter),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontFamily = Inter),
    bodySmall = DefaultTypography.bodySmall.copy(fontFamily = Inter),
    labelLarge = DefaultTypography.labelLarge.copy(fontFamily = Inter),
    labelMedium = DefaultTypography.labelMedium.copy(fontFamily = Inter),
    labelSmall = DefaultTypography.labelSmall.copy(fontFamily = Inter),
)
