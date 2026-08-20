package au.edu.unimelb.nightwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.edu.unimelb.nightwatch.core.LogEntry
import au.edu.unimelb.nightwatch.core.Phase
import au.edu.unimelb.nightwatch.core.SafetyState
import au.edu.unimelb.nightwatch.ui.theme.Alarm
import au.edu.unimelb.nightwatch.ui.theme.Caution
import au.edu.unimelb.nightwatch.ui.theme.Safe

/**
 * Single-screen UI.
 *
 * Deliberately built so the primary action is always the largest target on the
 * screen: during a countdown "I'm OK" must be hittable without looking, possibly
 * while walking fast in the dark.
 */
@Composable
fun SafeWalkScreen(
    state: SafetyState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMarkSafe: () -> Unit,
    onAlertNow: () -> Unit,
    onStandDown: () -> Unit,
    onShareLocation: () -> Unit,
    onDismissCaution: () -> Unit
) {
    val accent = when (state.phase) {
        Phase.ALERTED, Phase.CONFIRMING -> Alarm
        Phase.CAUTION -> Caution
        else -> Safe
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusHeader(state, accent)

            when (state.phase) {
                Phase.CONFIRMING -> CountdownCard(state, onMarkSafe, onAlertNow)
                Phase.ALERTED -> AlertedCard(state, onStandDown)
                Phase.CAUTION -> CautionCard(onShareLocation, onDismissCaution)
                else -> Unit
            }

            SensorGrid(state)
            ContactsRow(state)
            DecisionLog(state)

            Spacer(Modifier.height(8.dp))

            if (state.isMonitoring) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("End safe walk")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Safe)
                ) {
                    Text("Start safe walk", fontWeight = FontWeight.Bold)
                }
            }

            Text(
                "Sensing runs on your device. Nothing is shared unless an alert fires.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusHeader(state: SafetyState, accent: Color) {
    Column {
        Text(
            "NIGHTWATCH",
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(accent, CircleShape)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                when (state.phase) {
                    Phase.IDLE -> "Not monitoring"
                    Phase.MONITORING -> "Monitoring"
                    Phase.CAUTION -> "Unlit stretch"
                    Phase.CONFIRMING -> "Checking in"
                    Phase.ALERTED -> "Contacts notified"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            "${state.activity.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                "%02d:%02d".format(state.elapsedSeconds / 60, state.elapsedSeconds % 60),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CountdownCard(state: SafetyState, onMarkSafe: () -> Unit, onAlertNow: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FF5C70))
    ) {
        Column(
            Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.threat?.label ?: "Possible incident", color = Alarm, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                state.threat?.prompt.orEmpty(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            Box(contentAlignment = Alignment.Center) {
                val total = state.threat?.countdownSeconds ?: 1
                CircularProgressIndicator(
                    progress = { state.secondsRemaining.toFloat() / total },
                    modifier = Modifier.size(120.dp),
                    color = Alarm,
                    strokeWidth = 8.dp
                )
                Text("${state.secondsRemaining}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Alerting your emergency contacts",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onMarkSafe,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Safe)
            ) {
                Text("I'm OK — false alarm", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAlertNow, modifier = Modifier.fillMaxWidth()) {
                Text("Alert my emergency contacts now", color = Alarm)
            }
        }
    }
}

@Composable
private fun AlertedCard(state: SafetyState, onStandDown: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FF5C70))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Emergency contacts notified", color = Alarm, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            state.contactsNotified.forEach {
                Text("• $it — location sent", fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "If you are in danger, call 000.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onStandDown, modifier = Modifier.fillMaxWidth()) {
                Text("I'm safe now — stand down")
            }
        }
    }
}

@Composable
private fun CautionCard(onShare: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x33FFB454))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Poorly lit stretch", color = Caution, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Share your live location with your emergency contacts until you're back in the light?",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Caution)
                ) { Text("Share location", color = Color(0xFF241A06)) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Not now")
                }
            }
        }
    }
}

@Composable
private fun SensorGrid(state: SafetyState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SensorTile("Motion", "%.2f g".format(state.accelerationG), Modifier.weight(1f))
        SensorTile("Sound", "${state.soundDb.toInt()} dB", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SensorTile("Light", "${state.lightLux.toInt()} lux", Modifier.weight(1f))
        SensorTile(
            "Location",
            state.locationAccuracyM?.let { "±%.0f m".format(it) } ?: "no fix",
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun SensorTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ContactsRow(state: SafetyState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("3 emergency contacts", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    if (state.sharingLiveLocation) "Live location shared"
                    else "We'll alert them if a threat is detected",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (state.isMonitoring) "ACTIVE" else "OFF",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (state.isMonitoring) Safe else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DecisionLog(state: SafetyState) {
    if (state.log.isEmpty()) return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF080E1C))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "DECISION LOG",
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            state.log.takeLast(8).forEach { entry ->
                Text(
                    entry.message,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when (entry.severity) {
                        LogEntry.Severity.GOOD -> Safe
                        LogEntry.Severity.WARN -> Caution
                        LogEntry.Severity.ALERT -> Alarm
                        LogEntry.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
