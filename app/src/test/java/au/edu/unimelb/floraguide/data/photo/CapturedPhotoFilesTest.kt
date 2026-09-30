package au.edu.unimelb.floraguide.data.photo

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapturedPhotoFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val captureId = "12345678-1234-1234-1234-123456789abc"
    private val name get() = "observation-$captureId.jpg"

    @Test fun deletesOnlyAnOwnedUnsentCapture() {
        val root = temporary.newFolder("photos")
        val photo = File(root, name).apply { writeText("test bytes") }
        val files = CapturedPhotoFiles(root)
        assertTrue(files.belongsToCapture(photo.absolutePath, captureId))
        assertTrue(files.discardUnsent(photo.absolutePath))
        assertFalse(photo.exists())
    }

    @Test fun cleanupOfAnAbsentOwnedFileIsIdempotent() {
        val root = temporary.newFolder("photos")
        val files = CapturedPhotoFiles(root)
        val path = File(root, name).absolutePath
        assertTrue(files.discardUnsent(path))
        assertTrue(files.discardUnsent(path))
    }

    @Test fun refusesAFileOutsideThePhotoDirectory() {
        val root = temporary.newFolder("photos")
        val outside = File(temporary.root, name).apply { writeText("retain") }
        assertFalse(CapturedPhotoFiles(root).discardUnsent(outside.absolutePath))
        assertTrue(outside.exists())
    }

    @Test fun refusesLegacyNamesAndSubdirectories() {
        val root = temporary.newFolder("photos")
        val oldPhoto = File(root, "observation-1234567890.jpg").apply { writeText("retain") }
        val nested = File(File(root, "nested").apply { mkdir() }, name).apply { writeText("retain") }
        val files = CapturedPhotoFiles(root)
        assertFalse(files.discardUnsent(oldPhoto.absolutePath))
        assertFalse(files.discardUnsent(nested.absolutePath))
        assertTrue(oldPhoto.exists())
        assertTrue(nested.exists())
    }

    @Test fun refusesDirectoryAndOtherCaptureIdentity() {
        val root = temporary.newFolder("photos")
        val directory = File(root, name).apply { mkdir() }
        val files = CapturedPhotoFiles(root)
        assertFalse(files.discardUnsent(directory.absolutePath))
        assertTrue(directory.isDirectory)
        assertFalse(files.belongsToCapture(directory.absolutePath, "different-id"))
    }
}
