package au.edu.unimelb.floraguide.domain.usecase

import java.io.IOException

const val PHOTO_UPLOAD_TIMEOUT_MS = 30_000L

/** A foreground upload deadline, not a cancellation caused by navigation or account changes. */
class PhotoUploadTimeoutException(
    val timeoutMillis: Long = PHOTO_UPLOAD_TIMEOUT_MS,
) : IOException("The photo upload did not complete within ${timeoutMillis / 1_000} seconds.")
