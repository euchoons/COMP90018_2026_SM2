package au.edu.unimelb.floraguide.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.components.CameraCaptureCard
import au.edu.unimelb.floraguide.ui.components.HabitatSelector
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.SectionHeading

@Composable
fun ScanScreen(
    state: FloraGuideUiState,
    onHabitatSelected: (Habitat) -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onUseDemoLocation: () -> Unit,
    onPhotoCaptured: (String, Float?) -> Unit,
    onGuidedDemo: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by rememberSaveable { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationGranted by rememberSaveable { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var stabilityGateEnabled by rememberSaveable { mutableStateOf(true) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        cameraGranted = granted(Manifest.permission.CAMERA)
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Camera-only permission requests must not override an explicit location skip.
        val requestedLocation = results.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
            results.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (requestedLocation) onPermissionResult(locationGranted)
    }
    LaunchedEffect(locationGranted) { if (locationGranted) onPermissionResult(true) }
    val sensors = state.sensorSnapshot.availability
    val canGate = sensors.accelerometer && sensors.gyroscope
    val captureEnabled = !stabilityGateEnabled || !canGate || state.sensorSnapshot.isStable
    val captureHint = if (!captureEnabled) "Hold still before capturing" else "Ready to capture"
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading(title = "Observe a plant", subtitle = "Photo and location are separate inputs; either can have its own status.") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Location for ALA", fontWeight = FontWeight.Bold)
                    Text(state.locationStatus, style = MaterialTheme.typography.bodySmall)
                    Text("Wait for a device fix before capture. Without a usable location, identification still runs but ALA is skipped.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = {
                            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                                onPermissionResult(true)
                            } else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text("Enable / refresh") }
                        FilledTonalButton(onClick = onUseDemoLocation) { Text("Skip location") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Observation habitat", fontWeight = FontWeight.Bold)
                Text("Recorded as metadata. Only the guided demo uses synthetic habitat priors.", style = MaterialTheme.typography.bodySmall)
                HabitatSelector(state.selectedHabitat, onHabitatSelected)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stability-gated capture", fontWeight = FontWeight.Bold)
                    Text(if (canGate) "Accelerometer + gyroscope" else "Sensor unavailable: manual capture", style = MaterialTheme.typography.bodySmall)
                }
                Switch(stabilityGateEnabled && canGate, { stabilityGateEnabled = it }, enabled = canGate)
            }
        }
        if (cameraGranted) item {
            CameraCaptureCard(snapshot = state.sensorSnapshot, captureEnabled = captureEnabled, captureHint = captureHint,
                onPhotoCaptured = onPhotoCaptured, onError = onError)
        } else item {
            Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable camera")
            }
        }
        item { FilledTonalButton(onClick = onGuidedDemo, modifier = Modifier.fillMaxWidth()) { Text("Run explicit offline guided demo") } }
        item {
            InformationCard("What is shared", "Capturing uploads the photo to Firebase and sends the stored image to Pl@ntNet. " +
                "When location is enabled, the capture coordinates and candidate names are sent to ALA for historical lookups. " +
                "Saving an observation is a separate local action. Nothing is submitted as a new ALA record.")
        }
    }
}
