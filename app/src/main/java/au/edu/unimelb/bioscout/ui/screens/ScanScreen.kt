package au.edu.unimelb.bioscout.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import au.edu.unimelb.bioscout.domain.model.Habitat
import au.edu.unimelb.bioscout.ui.BioScoutUiState
import au.edu.unimelb.bioscout.ui.components.CameraCaptureCard
import au.edu.unimelb.bioscout.ui.components.HabitatSelector
import au.edu.unimelb.bioscout.ui.components.InformationCard
import au.edu.unimelb.bioscout.ui.components.SectionHeading
import au.edu.unimelb.bioscout.ui.components.StatusPill

@Composable
fun ScanScreen(
    state: BioScoutUiState,
    onHabitatSelected: (Habitat) -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onUseDemoLocation: () -> Unit,
    onPhotoCaptured: (String) -> Unit,
    onGuidedDemo: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var locationGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var stabilityGateEnabled by rememberSaveable { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        cameraGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        onPermissionResult(locationGranted)
    }

    LaunchedEffect(locationGranted) {
        if (locationGranted) onPermissionResult(true)
    }

    val availability = state.sensorSnapshot.availability
    val canUseStabilityGate = availability.accelerometer && availability.gyroscope
    val captureEnabled = !stabilityGateEnabled || !canUseStabilityGate || state.sensorSnapshot.isStable
    val captureHint = when {
        stabilityGateEnabled && canUseStabilityGate && !state.sensorSnapshot.isStable ->
            "Hold still — capture unlocks when the phone is stable"
        stabilityGateEnabled && canUseStabilityGate -> "Stable — ready to capture"
        else -> "Manual capture fallback active"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeading(
                title = "Observe a plant",
                subtitle = "Camera quality and context are collected separately so each cue can fail gracefully.",
            )
        }

        item {
            LocationCard(
                status = state.locationStatus,
                usingDemo = state.usingDemoLocation,
                locationGranted = locationGranted,
                onEnableLiveLocation = {
                    if (locationGranted) {
                        onPermissionResult(true)
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                onUseDemo = onUseDemoLocation,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Microhabitat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Change this after analysis to see the ranking react immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HabitatSelector(selected = state.selectedHabitat, onSelected = onHabitatSelected)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Stability-gated capture",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (canUseStabilityGate) {
                                "Accelerometer + gyroscope fusion"
                            } else {
                                "Missing sensor: manual fallback"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = stabilityGateEnabled && canUseStabilityGate,
                        onCheckedChange = { stabilityGateEnabled = it },
                        enabled = canUseStabilityGate,
                    )
                }
            }
        }

        if (cameraGranted) {
            item {
                CameraCaptureCard(
                    snapshot = state.sensorSnapshot,
                    captureEnabled = captureEnabled,
                    captureHint = captureHint,
                    onPhotoCaptured = onPhotoCaptured,
                    onError = onError,
                )
            }
        } else {
            item {
                PermissionCard(
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    onGuidedDemo = onGuidedDemo,
                )
            }
        }

        item {
            InformationCard(
                title = "Model status: prototype adapter",
                body = "The capture is real, but the prototype image candidate scores are deterministic. This prevents an unvalidated model from being presented as reliable AI while preserving the complete integration surface.",
            )
        }
    }
}

@Composable
private fun LocationCard(
    status: String,
    usingDemo: Boolean,
    locationGranted: Boolean,
    onEnableLiveLocation: () -> Unit,
    onUseDemo: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(text = "Location context", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusPill(
                    label = if (usingDemo) "Demo coordinate" else "Live GPS / network fix",
                    positive = !usingDemo,
                )
            }
            when {
                !usingDemo -> {
                    FilledTonalButton(onClick = onUseDemo) {
                        Icon(Icons.Default.LocationOff, contentDescription = null)
                        Text(" Demo")
                    }
                }
                else -> {
                    FilledTonalButton(onClick = onEnableLiveLocation) {
                        Text(if (locationGranted) "Use live" else "Enable")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    onRequestPermissions: () -> Unit,
    onGuidedDemo: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Camera and location permissions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "Camera captures the observation. Location is used only to query nearby records; saved observations round coordinates before persistence.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("Enable camera and location")
            }
            FilledTonalButton(onClick = onGuidedDemo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  Continue with guided sample")
            }
        }
    }
}
