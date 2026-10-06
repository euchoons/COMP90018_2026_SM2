package au.edu.unimelb.floraguide.ui

import au.edu.unimelb.floraguide.domain.repository.NetworkStatus

enum class UploadFailureKind { NO_INTERNET, TIMEOUT }

/** Immutable UI state; no timer is attached to the Compose lifecycle. */
data class UploadFailureDialogState(
    val requestGeneration: Long,
    val captureId: String,
    val userId: String,
    val kind: UploadFailureKind,
) {
    val title: String
        get() = if (kind == UploadFailureKind.NO_INTERNET) "Upload failed" else "Upload timed out"

    val message: String
        get() = if (kind == UploadFailureKind.NO_INTERNET) {
            "No internet connection was detected. The photo upload timed out after 30 seconds. " +
                "Please reconnect and try again."
        } else {
            "The photo could not be uploaded within 30 seconds. " +
                "Please check your connection and try again."
        }
}

internal fun uploadFailureKind(status: NetworkStatus): UploadFailureKind =
    if (status == NetworkStatus.UNAVAILABLE) UploadFailureKind.NO_INTERNET else UploadFailureKind.TIMEOUT
