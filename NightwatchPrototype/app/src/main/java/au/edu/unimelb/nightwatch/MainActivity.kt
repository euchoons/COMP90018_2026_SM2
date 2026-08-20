package au.edu.unimelb.nightwatch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import au.edu.unimelb.nightwatch.service.SafeWalkService
import au.edu.unimelb.nightwatch.ui.SafeWalkScreen
import au.edu.unimelb.nightwatch.ui.theme.NightwatchTheme

class MainActivity : ComponentActivity() {

    private val engine by lazy { (application as NightwatchApp).engine }

    /**
     * All three permissions are requested together because the app is close to
     * useless without them: no location means an alert with no position, no
     * microphone means no distress-sound detection.
     *
     * Explain this before requesting in the real build — a cold permission
     * prompt for the microphone on a safety app gets denied a lot.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { SafeWalkService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NightwatchTheme {
                val state by engine.state.collectAsState()

                SafeWalkScreen(
                    state = state,
                    onStart = { requestPermissionsThenStart() },
                    onStop = { SafeWalkService.stop(this) },
                    onMarkSafe = engine::markSafe,
                    onAlertNow = engine::raiseAlertNow,
                    onStandDown = engine::standDown,
                    onShareLocation = engine::shareLocationFromCaution,
                    onDismissCaution = engine::dismissCaution
                )
            }
        }
    }

    private fun requestPermissionsThenStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
