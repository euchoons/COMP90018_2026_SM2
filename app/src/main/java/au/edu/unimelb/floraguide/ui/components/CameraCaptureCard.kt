package au.edu.unimelb.floraguide.ui.components

import android.content.Context
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import au.edu.unimelb.floraguide.domain.model.LightCondition
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import java.io.File
import java.time.Instant

@Composable
fun CameraCaptureCard(
    snapshot: SensorSnapshot,
    captureEnabled: Boolean,
    captureHint: String,
    onPhotoCaptured: (String, Float?) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var isRearCamera by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, previewView) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        // The provider future can complete after the user has already left the Scan screen.
        // Binding at that point would reopen the camera for a preview nobody can see.
        var disposed = false
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                if (disposed) return@addListener
                runCatching {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    // Tablets and Chromebooks may only have a front camera.
                    val selector = when {
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> error("No camera is available on this device.")
                    }
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(CAPTURE_RESOLUTION)
                        .build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        capture,
                    )
                    imageCapture = capture
                    isRearCamera = selector == CameraSelector.DEFAULT_BACK_CAMERA
                    cameraReady = true
                    cameraFailed = false
                }.onFailure { error ->
                    cameraReady = false
                    cameraFailed = true
                    onError(error.message ?: "Camera could not start.")
                }
            },
            executor,
        )

        onDispose {
            disposed = true
            provider?.unbindAll()
            imageCapture = null
            cameraReady = false
        }
    }

    // With auto-rotate locked the display stays portrait while the phone is held sideways, so the
    // JPEG orientation follows the physical orientation rather than the display rotation.
    DisposableEffect(imageCapture) {
        val capture = imageCapture ?: return@DisposableEffect onDispose { }
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                capture.targetRotation = surfaceRotationFor(orientation)
            }
        }
        // Without an accelerometer the display rotation chosen at bind time is kept.
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    val heading = snapshot.headingForCapture(isRearCamera)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(430.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // Warning labels are longer than readings, so the pills wrap on narrow phones.
        FlowRow(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraOverlayPill(
                text = when {
                    !snapshot.canMeasureStability -> "Stability n/a"
                    snapshot.isStable -> "Steady"
                    else -> "Hold still"
                },
                positive = snapshot.canMeasureStability && snapshot.isStable,
            )
            CameraOverlayPill(
                text = lightLabel(snapshot),
                positive = snapshot.lightCondition == LightCondition.USABLE,
            )
            CameraOverlayPill(
                text = when {
                    !isRearCamera || snapshot.headingDegrees == null -> "Heading n/a"
                    snapshot.compassNeedsCalibration -> "Calibrate compass"
                    else -> "${snapshot.headingDegrees.toInt()}°"
                },
                positive = heading != null,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.50f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    cameraReady -> captureHint
                    cameraFailed -> "Camera unavailable — the guided demo on Home still works"
                    else -> "Starting CameraX…"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    // Freeze the shutter-time value; JPEG saving can outlive this sensor reading.
                    val captureHeading = heading
                    isSaving = true
                    capturePhoto(
                        context = context,
                        imageCapture = capture,
                        onSaved = { path ->
                            isSaving = false
                            onPhotoCaptured(path, captureHeading)
                        },
                        onError = { message ->
                            isSaving = false
                            onError(message)
                        },
                    )
                },
                enabled = cameraReady && captureEnabled && !isSaving,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                contentPadding = ButtonDefaults.ContentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color.White.copy(alpha = 0.45f),
                ),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Capture photo",
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraOverlayPill(text: String, positive: Boolean) {
    Surface(
        color = if (positive) Color(0xDDDCFCE7) else Color(0xDDF3F4F3),
        contentColor = if (positive) Color(0xFF14532D) else Color(0xFF374151),
        shape = CircleShape,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Caps captures near 1920x1440 (4:3, ~2.8 MP) instead of the full sensor, which is 12–50 MP on
 * current phones. Every capture is uploaded to Pl@ntNet and Firebase, and the Pl@ntNet round
 * trip was measured with a 1123x1600 photo, so this keeps upload size down without dropping
 * below a resolution known to work. Rationale: docs/CAMERA_AND_SENSOR_VALIDATION.md.
 */
private val CAPTURE_RESOLUTION = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1920, 1440),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
        ),
    )
    .build()

/** Maps [OrientationEventListener] degrees to a Surface rotation, as in the CameraX guide. */
private fun surfaceRotationFor(orientationDegrees: Int): Int = when (orientationDegrees) {
    in 45 until 135 -> Surface.ROTATION_270
    in 135 until 225 -> Surface.ROTATION_180
    in 225 until 315 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val directory = File(context.filesDir, "photos").apply { mkdirs() }
    val file = File(directory, "observation-${Instant.now().toEpochMilli()}.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        output,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(file.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "Photo capture failed.")
            }
        },
    )
}
