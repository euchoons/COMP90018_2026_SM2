package au.edu.unimelb.floraguide.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun `small text meets 4_5 contrast on actual composited surfaces`() {
        for (scheme in listOf(LightColours, DarkColours)) {
            for (canvas in listOf(scheme.background, scheme.surface, scheme.primaryContainer)) {
                val surfaces = listOf(
                    "plain caption" to canvas,
                    "positive status pill" to scheme.surfaceVariant.compositeOver(canvas),
                    // copy(alpha) REPLACES the old alpha; it does not multiply it.
                    "ranking panel" to scheme.surfaceVariant.copy(alpha = 0.55f).compositeOver(canvas),
                    "selected card" to scheme.primaryContainer.copy(alpha = 0.45f).compositeOver(canvas),
                )
                for ((name, background) in surfaces) checkContrast(name, scheme.onSurfaceVariant, background, 4.5)
                checkContrast("error message", scheme.error, canvas, 4.5)
                checkContrast("secondary caption", scheme.onSecondaryContainer, scheme.secondaryContainer.compositeOver(canvas), 4.5)
            }
        }
    }

    @Test fun `input outlines remain visible in both themes`() {
        for (scheme in listOf(LightColours, DarkColours)) {
            checkContrast("input outline", scheme.outline, scheme.surface, 3.0)
            checkContrast("input outline on background", scheme.outline, scheme.background, 3.0)
        }
    }

    private fun checkContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val a = foreground.compositeOver(background).luminance().toDouble()
        val b = background.luminance().toDouble()
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("$name needs $minimum:1 contrast; got $ratio:1", ratio >= minimum)
    }
}
