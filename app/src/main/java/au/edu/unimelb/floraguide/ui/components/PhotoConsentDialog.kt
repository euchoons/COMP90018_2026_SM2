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
import androidx.compose.ui.tooling.preview.Preview
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
                    "This photo has not been sent yet.\n\nProceeding to send will upload it to your private " +
                        "Firebase Storage area, reads it back, and sends the image to Pl@ntNet " +
                        "for plant identification.",
                )
                Text(
                    if (hasCaptureLocation) {
                        "The capture coordinates, rounded to three decimal places, and candidate " +
                            "plant names will also be sent to ALA for read-only historical lookups."
                    } else {
                        "This capture has no usable location. ALA will not be queried."
                    },
                )
                Text(
                    "This permission applies only to this specific photo.",
                )
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
@Preview(showBackground = true)
@Composable
fun PhotoConsentDialogPreview() {
    au.edu.unimelb.floraguide.ui.theme.FloraGuideTheme {
        PhotoConsentDialog(
            hasCaptureLocation = true,
            onAgree = {},
            onCancel = {}
        )
    }
}
