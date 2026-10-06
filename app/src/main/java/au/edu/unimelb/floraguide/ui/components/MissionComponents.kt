package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import au.edu.unimelb.floraguide.ui.MISSION_SPECIES_GOAL

/** "2 / 3" over "species", or "3 / 3" over "complete": the count is of species, never of saves. */
@Composable
fun MissionCounter(uniqueSpecies: Int, modifier: Modifier = Modifier) {
    val shown = minOf(uniqueSpecies, MISSION_SPECIES_GOAL)
    val complete = uniqueSpecies >= MISSION_SPECIES_GOAL
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (complete) "Mission complete" else "$shown of $MISSION_SPECIES_GOAL species"
        },
    ) {
        Text(
            text = "$shown / $MISSION_SPECIES_GOAL",
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = if (complete) "complete" else "species",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Says what counts, so a repeat or guided-demo save that leaves the counter unchanged is expected. */
@Composable
fun MissionNote(uniqueSpecies: Int) {
    Text(
        text = if (uniqueSpecies >= MISSION_SPECIES_GOAL) {
            "Mission complete: $uniqueSpecies different species recorded."
        } else {
            "Each different species from a live photo counts once; guided-demo saves don't count."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}
