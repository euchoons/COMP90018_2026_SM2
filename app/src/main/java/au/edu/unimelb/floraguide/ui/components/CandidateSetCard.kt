package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared outer container for the original scores and the final candidate set. */
@Composable
internal fun CandidateSetCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Follow the actual app surface, including an app-level dark-theme override.
    val darkSurface = colors.surface.luminance() < 0.5f
    val containerColor = when {
        !highlighted -> colors.surface
        darkSurface -> Color(0xFF183C2A)
        else -> Color(0xFFE6F4EA)
    }
    val contentColor = when {
        !highlighted -> colors.onSurface
        darkSurface -> Color(0xFFE2F3E7)
        else -> Color(0xFF173B25)
    }
    val borderColor = when {
        !highlighted -> colors.outlineVariant
        darkSurface -> Color(0xFF559C6D)
        else -> Color(0xFFA8D5B5)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

/**
 * Shared candidate card for both sections. Final candidates retain the existing
 * SelectableCard behaviour. Original scores are read-only, not no-op buttons.
 */
@Composable
internal fun CandidateResultCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        SelectableCard(
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            content = content,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Box(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}
