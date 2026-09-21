package au.edu.unimelb.floraguide.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.LightCondition
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.components.CameraCaptureCard
import au.edu.unimelb.floraguide.ui.components.HabitatSelector
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.LocationMap
import au.edu.unimelb.floraguide.ui.components.SectionHeading
import au.edu.unimelb.floraguide.ui.components.StatusPill

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
    // Once Android stops showing the dialog, relaunching the request returns "denied" instantly,
    // so the only way forward is the system app-settings page.
    var cameraPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        cameraGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        // The location-only request from LocationCard must not reset the camera state.
        if (Manifest.permission.CAMERA in results) {
            cameraPermanentlyDenied = !cameraGranted &&
                activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        }
        locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        onPermissionResult(locationGranted)
    }

    // Permissions can be granted from system settings while this screen stays composed.
    LifecycleResumeEffect(Unit) {
        if (!cameraGranted &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraGranted = true
            cameraPermanentlyDenied = false
        }
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (locationGranted != hasLocationPermission) {
            locationGranted = hasLocationPermission
            if (!hasLocationPermission) onPermissionResult(false)
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(locationGranted) {
        if (locationGranted) onPermissionResult(true)
    }

    val canUseStabilityGate = state.sensorSnapshot.canMeasureStability
    val captureEnabled = !stabilityGateEnabled || !canUseStabilityGate || state.sensorSnapshot.isStable
    // Light never blocks capture; it only qualifies the hint once capture is possible.
    val lightWarning = when (state.sensorSnapshot.lightCondition) {
        LightCondition.LOW -> "low light may blur the photo"
        LightCondition.VERY_BRIGHT -> "harsh light may wash out detail"
        LightCondition.USABLE, LightCondition.UNAVAILABLE -> null
    }
    val captureHint = when {
        stabilityGateEnabled && canUseStabilityGate && !state.sensorSnapshot.isStable ->
            "Hold still — capture unlocks when the phone is stable"
        stabilityGateEnabled && canUseStabilityGate ->
            lightWarning?.let { "Stable — $it" } ?: "Stable — ready to capture"
        else -> lightWarning?.let { "Manual capture — $it" } ?: "Manual capture fallback active"
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
                location = state.location,
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
                    text = "Demo rankings use habitat priors. Live species without validated habitat data use neutral priors.",
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
                    permanentlyDenied = cameraPermanentlyDenied,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
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
                title = "Cloud photo identification",
                body = "Taking a photo uploads it to your private Firebase Storage area and sends the stored image to Pl@ntNet for identification. The explicit guided demo stays offline.",
            )
        }
    }
}

@Composable
private fun LocationCard(
    location: GeoPoint,
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
        LocationMap(
            location = location,
            usingDemo = usingDemo,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun PermissionCard(
    permanentlyDenied: Boolean,
    onOpenSettings: () -> Unit,
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
                text = if (permanentlyDenied) {
                    "Camera access is turned off for FloraGuide. Allow it under Permissions in app settings, or continue with the guided sample."
                } else {
                    "Camera captures the observation. Location is used only to query nearby records; saved observations round coordinates before persistence."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
            } else {
                Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable camera and location")
                }
            }
            FilledTonalButton(onClick = onGuidedDemo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  Continue with guided sample")
            }
        }
    }
}
