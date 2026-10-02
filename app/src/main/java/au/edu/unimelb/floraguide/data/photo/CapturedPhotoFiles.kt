package au.edu.unimelb.floraguide.data.photo

import java.io.File
import java.nio.file.Files

/** Narrow file boundary for NEW CameraX captures. Never use this for cloud or saved-photo GC. */
class CapturedPhotoFiles(private val photosDirectory: File) {
    fun belongsToCapture(localPath: String, captureId: String): Boolean =
        isOwnedCapture(localPath) && File(localPath).name == "observation-$captureId.jpg"

    fun isOwnedCapture(localPath: String): Boolean = runCatching {
        val candidate = File(localPath)
        candidate.isAbsolute &&
            CAPTURE_NAME.matches(candidate.name) &&
            !Files.isSymbolicLink(candidate.toPath()) &&
            candidate.canonicalFile.parentFile == photosDirectory.canonicalFile
    }.getOrDefault(false)

    /** Call ONLY for a capture that never received consent. Safe on an already absent file. */
    fun discardUnsent(localPath: String): Boolean {
        if (!isOwnedCapture(localPath)) return false
        val candidate = File(localPath)
        if (!candidate.exists()) return true
        if (!candidate.isFile) return false
        return candidate.delete()
    }

    private companion object {
        val CAPTURE_NAME = Regex(
            "observation-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg",
        )
    }
}
