package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun PhotoConsentDialog(
    hasCaptureLocation: Boolean,
    onAgree: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("photo-consent-dialog"),
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
        title = { Text("Approve online identification?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Your photo is currently private and has not been shared online. " +
                        "Proceeding will upload the image securely to your private online folder and " +
                        "verify it using Pl@ntNet (our automated global verification database).",
                )
                Text(
                    if (hasCaptureLocation) {
                        "If location access is active, a privacy-safe rounded version of your coordinates " +
                            "will be sent to the Atlas of Living Australia (ALA) registry to check what plant history exists " +
                            "within this specific section of location. " +
                            "This permission applies only to this specific photo. " +
                            "It is used strictly for a read-only historical search and " +
                            "will never be published as public records"
                    } else {
                        "Location access is inactive. ALA will not be queried."
                    },
                )
//                Text(
//                    "This choice applies only to this photo, including retries. It does not save " +
//                        "an observation. Saving is a separate action; saved observations may sync " +
//                        "through your Firebase account.",
//                )
//                Text(
//                    "Leaving later cannot recall data already sent. Uploaded photos may remain " +
//                        "in Firebase even when you do not save; automatic cloud cleanup is not " +
//                        "implemented yet.",
//                    style = MaterialTheme.typography.bodySmall,
//                )
                Text("Tapping Cancel sends nothing from your phone")
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree, modifier = Modifier.testTag("photo-consent-agree")) {
                Text("Agree and identify")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("photo-consent-cancel")) {
                Text("Cancel")
            }
        },
    )
}
