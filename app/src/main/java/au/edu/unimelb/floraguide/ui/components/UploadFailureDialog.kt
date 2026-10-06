package au.edu.unimelb.floraguide.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import au.edu.unimelb.floraguide.ui.UploadFailureDialogState

@Composable
fun UploadFailureDialog(
    state: UploadFailureDialogState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(state.title) },
        text = { Text(state.message) },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = retryEnabled) { Text("Retry") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
