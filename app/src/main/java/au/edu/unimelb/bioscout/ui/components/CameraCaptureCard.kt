package au.edu.unimelb.bioscout.ui.components

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import au.edu.unimelb.bioscout.domain.model.SensorSnapshot
import java.io.File
import java.time.Instant

@Composable
fun CameraCaptureCard(
    snapshot: SensorSnapshot,
    captureEnabled: Boolean,
    captureHint: String,
    onPhotoCaptured: (String) -> Unit,
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

    DisposableEffect(lifecycleOwner, previewView) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                runCatching {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    imageCapture = capture
                    cameraReady = true
                }.onFailure { error ->
                    cameraReady = false
                    onError(error.message ?: "Camera could not start.")
                }
            },
            executor,
        )

        onDispose {
            provider?.unbindAll()
            imageCapture = null
        }
    }

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

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraOverlayPill(
                text = if (snapshot.isStable) "Steady" else "Hold still",
                positive = snapshot.isStable,
            )
            CameraOverlayPill(
                text = snapshot.lightLux?.let { "${it.toInt()} lux" } ?: "Light n/a",
                positive = snapshot.lightLux?.let { it in 25f..20_000f } ?: false,
            )
            CameraOverlayPill(
                text = snapshot.headingDegrees?.let { "${it.toInt()}°" } ?: "Heading n/a",
                positive = snapshot.headingDegrees != null,
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
                text = if (cameraReady) captureHint else "Starting CameraX…",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    isSaving = true
                    capture.targetRotation = previewView.display?.rotation ?: capture.targetRotation
                    capturePhoto(
                        context = context,
                        imageCapture = capture,
                        onSaved = { path ->
                            isSaving = false
                            onPhotoCaptured(path)
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
