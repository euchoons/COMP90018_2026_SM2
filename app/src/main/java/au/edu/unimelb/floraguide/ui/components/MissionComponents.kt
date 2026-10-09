package au.edu.unimelb.floraguide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.ui.MISSION_SPECIES_GOAL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** "2 / 3" over "species", or "3 / 3" over "complete": the count is of species, never of saves. */
@Composable
fun MissionCounter(uniqueSpecies: Int, modifier: Modifier = Modifier) {
    val shown = minOf(uniqueSpecies, MISSION_SPECIES_GOAL)
    val complete = uniqueSpecies >= MISSION_SPECIES_GOAL
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (complete) "Mission complete!" else "$shown of $MISSION_SPECIES_GOAL species"
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
            "Each different species from a live photo counts once."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/**
 * A short shower of little flowers for a completed mission. It only draws, so touches still reach
 * the screen underneath, and [onFinished] runs once the last flower has fallen.
 */
@Composable
fun FlowerConfetti(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val flowers = remember { List(FLOWER_COUNT) { Flower(Random.Default) } }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        time.animateTo(1f, tween(CONFETTI_MILLIS, easing = LinearEasing))
        onFinished()
    }
    Canvas(modifier.fillMaxSize()) {
        flowers.forEach { it.draw(this, time.value) }
    }
}

private class Flower(random: Random) {
    private val across = random.nextFloat()
    private val delay = random.nextFloat() * 0.3f
    private val fall = 0.9f + random.nextFloat() * 0.5f
    private val sway = 0.015f + random.nextFloat() * 0.03f
    private val phase = random.nextFloat() * 2 * PI.toFloat()
    private val radiusDp = 6f + random.nextFloat() * 6f
    private val spin = (random.nextFloat() - 0.5f) * 540f
    private val petal = PETAL_COLOURS[random.nextInt(PETAL_COLOURS.size)]

    fun draw(scope: DrawScope, time: Float) = with(scope) {
        val t = ((time - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (t == 0f) return@with
        val radius = radiusDp.dp.toPx()
        val centre = Offset(
            size.width * (across + sway * sin(phase + t * 10f)),
            -2 * radius + (size.height + 4 * radius) * t * fall,
        )
        val alpha = if (t > 0.8f) (1f - t) / 0.2f else 1f
        rotate(spin * t, centre) {
            repeat(5) { i ->
                val angle = i * 2 * PI.toFloat() / 5
                drawCircle(petal, radius * 0.55f, centre + Offset(cos(angle), sin(angle)) * (radius * 0.6f), alpha)
            }
            drawCircle(FLOWER_CENTRE, radius * 0.35f, centre, alpha)
        }
    }
}

private const val FLOWER_COUNT = 48
private const val CONFETTI_MILLIS = 2400
private val FLOWER_CENTRE = Color(0xFFFFC107)
private val PETAL_COLOURS = listOf(
    Color(0xFFF48FB1), Color(0xFFCE93D8), Color(0xFF90CAF9),
    Color(0xFFFFAB91), Color(0xFFA5D6A7), Color(0xFFFFF59D),
)
