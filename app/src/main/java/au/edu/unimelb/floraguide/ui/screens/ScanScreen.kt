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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.components.CameraCaptureCard
import au.edu.unimelb.floraguide.ui.components.HabitatSelector
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.SectionHeading
import au.edu.unimelb.floraguide.ui.components.captureGuidance

@Composable
fun ScanScreen(
    state: FloraGuideUiState,
    onHabitatSelected: (Habitat) -> Unit,
    onStabilityGateChanged: (Boolean) -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onUseDemoLocation: () -> Unit,
    onCaptureStarted: () -> String?,
    onPhotoCaptured: (String, Float?) -> Unit,
    onGuidedDemo: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by rememberSaveable { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationGranted by rememberSaveable { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var preciseLocation by rememberSaveable { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    // Once Android stops showing the dialog, relaunching the request returns "denied" instantly,
    // so the only way forward is the system app-settings page.
    var cameraPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        cameraGranted = granted(Manifest.permission.CAMERA)
        // A location-only request must not reset the camera state.
        if (Manifest.permission.CAMERA in results) {
            cameraPermanentlyDenied = !cameraGranted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        }
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        preciseLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        // Camera-only permission requests must not override an explicit location skip.
        val requestedLocation = results.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
            results.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (requestedLocation) onPermissionResult(locationGranted)
    }
    // Permissions can change in system settings while this screen is away. Revoking one restarts the
    // app, and rememberSaveable would then restore the old "granted" value, so re-read them all.
    LifecycleResumeEffect(Unit) {
        cameraGranted = granted(Manifest.permission.CAMERA)
        if (cameraGranted) cameraPermanentlyDenied = false
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        preciseLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        onPauseOrDispose { }
    }
    // Re-runs after rotation or theme changes, so it must not override an explicit skip.
    LaunchedEffect(locationGranted, preciseLocation, state.pendingPhotoConsent?.captureId) {
        if (locationGranted && !state.locationSkipped && state.pendingPhotoConsent == null) {
            onPermissionResult(true)
        }
    }
    val canGate = state.sensorSnapshot.canMeasureStability
    val guidance = captureGuidance(state.sensorSnapshot, state.stabilityGateEnabled, state.pendingPhotoConsent != null)
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading(title = "Observe a plant", subtitle = "Your photo capture and GPS location check run independently. You can still scan a plant even if your location signal is still loading.") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Location status for ALA local history check", fontWeight = FontWeight.Bold)
                    Text(state.locationStatus, style = MaterialTheme.typography.bodySmall)
                    Text("For the most accurate results, wait for a confirmed GPS signal before taking a picture. Without location access, the photo scanner will still work perfectly, but the app skips looking up local history records.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = {
                            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                                onPermissionResult(true)
                            } else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text("Enable / refresh GPS") }
                        FilledTonalButton(onClick = onUseDemoLocation) { Text("Skip location") }
                    }
                    if (locationGranted && !preciseLocation) {
                        Text("Approximate location can find nearby records, but saving a plant needs precise location.",
                            style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text("Use precise location") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Observation habitat", fontWeight = FontWeight.Bold)
                Text("Recorded as a reference note. ", style = MaterialTheme.typography.bodySmall)
                HabitatSelector(state.selectedHabitat, onHabitatSelected)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stability-gated capture", fontWeight = FontWeight.Bold)
                    Text(guidance.switchSummary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.stabilityGateEnabled && canGate, onStabilityGateChanged, enabled = canGate)
            }
        }
        if (cameraGranted) item {
            CameraCaptureCard(snapshot = state.sensorSnapshot, guidance = guidance,
                headingDeclinationDegrees = state.headingDeclinationDegrees,
                onCaptureStarted = onCaptureStarted, onPhotoCaptured = onPhotoCaptured, onError = onError)
        } else if (cameraPermanentlyDenied) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Camera access is turned off for FloraGuide. Allow it under Permissions in app settings, or use the offline guided demo below.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
            }
        } else item {
            Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable camera")
            }
        }
        item { FilledTonalButton(onClick = onGuidedDemo, modifier = Modifier.fillMaxWidth()) { Text("Run explicit offline guided demo") } }
        item {
            InformationCard("What is shared", "After capture, you choose whether to use online identification. " +
                "Only after you agree is the photo uploaded to Firebase and the stored image sent to Pl@ntNet. " +
                "With a usable capture location, rounded coordinates and candidate names are sent to ALA. " +
                "Saving is separate, and saved observations may sync through your Firebase account. " +
                "Nothing is submitted as a new ALA record. Cancel before agreeing to avoid sending this capture.")
        }
    }
}
