package au.edu.unimelb.floraguide.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import au.edu.unimelb.floraguide.di.AppContainer
import au.edu.unimelb.floraguide.domain.model.*
import au.edu.unimelb.floraguide.domain.repository.*
import au.edu.unimelb.floraguide.domain.usecase.*
import io.mockk.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real ViewModel + real use case + fake network/storage services. No live API quota is used. */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue14CloudWorkflowViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()
    private val container = mockk<AppContainer>(relaxed = true)
    private val auth = mockk<AuthRepository>(relaxed = true)
    private val records = mockk<ObservationRepository>()
    private val authFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(UserProfile(UID, null, null, false)))
    private val persisted = mutableListOf<Observation>()
    private val stored = MutableStateFlow<List<Observation>>(emptyList())
    private val journal = mutableMapOf<String, PendingPhotoRecord>()
    private val events = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val classifiedPaths = mutableListOf<String?>()
    private lateinit var registry: PendingPhotoRegistry
    private lateinit var model: FloraGuideViewModel
    private lateinit var store: PhotoStore
    private var uploads = 0
    private var downloads = 0
    private var classifications = 0
    private var failDownload = false
    private var failClassification = false
    private var hangDeletes = false
    private var firstClassificationGate: CompletableDeferred<Unit>? = null
    private val prediction = ImagePrediction(Species("test-plant", "Test plant", "Test species", emptySet(), emptyMap(), 0), 0.83, 1)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        registry = PendingPhotoRegistry(object : PendingPhotoRecordStore {
            override fun readAll() = journal.values.toList()
            override fun put(record: PendingPhotoRecord) { journal[record.gsUri] = record }
            override fun remove(gsUri: String) { journal.remove(gsUri) }
        }, "test-process")
        store = object : PhotoStore {
            override suspend fun uploadPhoto(localPath: String): StoredPhoto {
                assertTrue(File(localPath).exists())
                events += "upload"
                uploads++
                val path = "plant_photos/$UID/scan-00000000-0000-0000-0000-${uploads.toString().padStart(12, '0')}-${"a".repeat(64)}.jpg"
                val photo = StoredPhoto(path, "gs://test-bucket/$path", 5, "a".repeat(64), "image/jpeg")
                registry.registerUpload(UID, photo.gsUri)
                registry.uploadFinished(photo.gsUri)
                return photo
            }
            override suspend fun downloadPhoto(photo: StoredPhoto): File {
                events += "download"
                downloads++
                if (failDownload) throw IOException("download unavailable")
                return temporary.newFile("download-$downloads.jpg").apply { writeText("cloud") }
            }
            override suspend fun deletePhoto(gsUri: String) {
                if (hangDeletes) awaitCancellation() // offline: the SDK keeps retrying
                deleted += gsUri
            }
        }
        val classifier = object : ImageClassifier {
            override suspend fun classify(photoPath: String?): ImageClassification {
                classifiedPaths += photoPath
                if (photoPath == null) return ImageClassification(listOf(prediction), ImageSource.DEMO_ADAPTER)
                events += "classify"
                classifications++
                assertEquals("cloud", File(photoPath).readText())
                if (classifications == 1) firstClassificationGate?.let { gate ->
                    // A badly behaved old dependency must still not overwrite a new scan's UI.
                    withContext(NonCancellable) { gate.await(); throw IOException("late old failure") }
                }
                if (failClassification) throw IOException("identification unavailable")
                return ImageClassification(listOf(prediction), ImageSource.PLANTNET_LIVE, 10L)
            }
        }
        every { container.authRepository } returns auth
        every { auth.authState } returns authFlow
        every { auth.getCurrentUser() } answers { (authFlow.value as? AuthState.Authenticated)?.user }
        every { container.observationRepository } returns records
        every { records.observeAll() } returns stored
        coEvery { records.save(any()) } coAnswers { persisted += firstArg<Observation>(); Unit }
        every { container.pendingPhotos } returns registry
        every { container.photoStorage } returns store
        every { container.imageClassifier } returns classifier
        every { container.identifyStoredPhoto } returns IdentifyStoredPhotoUseCase(
            photoStore = store,
            classifier = classifier,
            onUndeliveredUpload = { photo -> registry.abandon(photo.gsUri) },
        )
        every { container.cleanupPendingPhotos } returns PendingPhotoCleanupUseCase(
            registry, { auth.getCurrentUser()?.uid }, { _, uri -> persisted.any { it.cloudPhotoUri == uri } }, { _, uri -> store.deletePhoto(uri) },
        )
        every { container.isPlantNetConfigured } returns true
        every { container.capturedPhotoFiles.belongsToCapture(any(), any()) } returns true
        every { container.rankCandidates } returns RankSpeciesCandidatesUseCase()
        every { container.locationTracker.snapshotForObservation() } returns GeoPoint(-37.7963, 144.9614, 10f)
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), any()) } returns
            NearbyContext(emptyMap(), ContextDataSource.ALA_LIVE, 8)
        model = ViewModelProvider(viewModelStore, FloraGuideViewModel.Factory(container))[FloraGuideViewModel::class.java]
        dispatcher.scheduler.runCurrent()
    }

    @After fun tearDown() {
        viewModelStore.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun camera(name: String = "camera.jpg") =
        temporary.newFile(name).apply {
            writeText("camera-original")
        }.absolutePath

    private fun scan(name: String = "camera.jpg") {
        model.goToScan()

        val captureId = requireNotNull(model.beginCapture())

        model.analyzeCapturedPhoto(
            photoPath = camera(name),
            captureHeadingDegrees = 42f,
        )

        model.approvePhotoUpload(captureId)
    }
    @Test fun liveScanUsesUploadThenDownloadThenDownloadedPathClassification() = runTest(dispatcher) {
        scan(); runCurrent()
        assertEquals(listOf("upload", "download", "classify"), events)
        assertTrue(classifiedPaths.single()!!.contains("download-1"))
        assertFalse(File(classifiedPaths.single()!!).exists())
        assertEquals("camera-original", File(model.uiState.value.photoPath!!).readText())
        assertEquals(ImageSource.PLANTNET_LIVE, model.uiState.value.imageSource)
        assertNotNull(model.uiState.value.storedPhoto)
    }

    @Test fun downloadFailureRetainsPhotoAndRetryDoesNotUploadAgain() = runTest(dispatcher) {
        failDownload = true
        scan(); runCurrent()
        val photo = model.uiState.value.storedPhoto
        val capture = model.uiState.value.capture
        assertNotNull(photo)
        assertTrue(model.uiState.value.analysisError.orEmpty().contains("Reading the stored photo"))
        assertTrue(registry.candidates(UID).isEmpty())
        failDownload = false
        model.retryIdentification(); model.retryIdentification(); runCurrent()
        assertEquals(1, uploads)
        assertEquals(2, downloads)
        assertEquals(1, classifications)
        assertEquals(photo, model.uiState.value.storedPhoto)
        assertEquals(capture, model.uiState.value.capture)
        assertEquals(42f, model.uiState.value.captureHeadingDegrees)
    }

    @Test fun identificationFailureKeepsPhotoRetryableUntilTheUserLeaves() = runTest(dispatcher) {
        failClassification = true
        scan(); runCurrent()
        assertTrue(model.uiState.value.analysisError.orEmpty().contains("Identifying the stored photo"))
        assertTrue(registry.candidates(UID).isEmpty())
        model.goHome()
        assertFalse(container.cleanupPendingPhotos(UID))
        assertEquals(1, deleted.size)
        assertEquals(AppScreen.HOME, model.uiState.value.screen)
        assertNull(model.uiState.value.message)
    }

    @Test fun navigatingToEveryDestinationQueuesOnlyTheUncommittedPhoto() = runTest(dispatcher) {
        val destinations: List<() -> Unit> = listOf(model::goHome, model::goToScan, model::goToCollection, model::goToAccount)
        destinations.forEachIndexed { index, navigate ->
            scan("camera-$index.jpg"); runCurrent()
            val uri = model.uiState.value.storedPhoto!!.gsUri
            navigate()
            assertEquals(uri, registry.candidates(UID).single().gsUri)
            assertFalse(container.cleanupPendingPhotos(UID))
            assertEquals(uri, deleted.last())
            assertNull(model.uiState.value.storedPhoto)
        }
    }

    @Test fun guestSignOutDeletesItsQueuedScanPhotoFirst() = runTest(dispatcher) {
        authFlow.value = AuthState.Authenticated(UserProfile(UID, null, null, true))
        runCurrent()
        scan(); runCurrent()
        val uri = model.uiState.value.storedPhoto!!.gsUri
        var deletedBeforeSignOut: List<String>? = null
        coEvery { auth.signOut() } coAnswers { deletedBeforeSignOut = deleted.toList() }
        model.signOut(); runCurrent()
        assertEquals(listOf(uri), deletedBeforeSignOut)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun offlineAccountDeletionGivesUpInBoundedTimeAndBlocksDataActions() = runTest(dispatcher) {
        scan(); runCurrent()
        model.goToAccount()
        hangDeletes = true
        model.deleteAccount(); runCurrent()
        model.importLocalObservations(); runCurrent()
        coVerify(exactly = 0) { records.importLocalObservations() }
        advanceUntilIdle()
        assertEquals("Pending photo cleanup has not finished. Reconnect and retry account deletion.", model.uiState.value.message)
        coVerify(exactly = 0) { auth.deleteAccount() }
        assertEquals(1, registry.candidates(UID).size)
    }

    @Test fun accountDeletionWaitsForABackgroundCleanupClaim() = runTest(dispatcher) {
        scan(); runCurrent()
        val uri = model.uiState.value.storedPhoto!!.gsUri
        model.goToAccount()
        assertTrue(registry.claim(uri)) // the background worker is mid-delete
        coEvery { auth.deleteAccount() } coAnswers { Result.success(Unit) }
        model.deleteAccount(); runCurrent()
        coVerify(exactly = 0) { auth.deleteAccount() }
        registry.releaseClaim(uri)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(uri), deleted)
        coVerify(exactly = 1) { auth.deleteAccount() }
    }

    @Test
    fun retappingObserveKeepsTheShutterTimeLocation() = runTest(dispatcher) {
        model.goToScan()

        val captureId = requireNotNull(model.beginCapture())

        // Simulate the location becoming unavailable after the shutter press.
        every {
            container.locationTracker.snapshotForObservation()
        } returns null

        // Re-tapping Observe must preserve the already frozen capture.
        model.goToScan()

        model.analyzeCapturedPhoto(
            photoPath = camera(),
            captureHeadingDegrees = 42f,
        )

        model.approvePhotoUpload(captureId)

        runCurrent()

        assertNotNull(model.uiState.value.capture?.location)
    }

    @Test fun savePersistsCloudMetadataAndLaterNavigationDoesNotDeleteThePhoto() = runTest(dispatcher) {
        scan(); runCurrent()
        val uri = model.uiState.value.storedPhoto!!.gsUri
        model.confirmSelectedObservation(); runCurrent()
        val observation = persisted.single()
        assertEquals(uri, observation.cloudPhotoUri)
        assertEquals(ImageSource.PLANTNET_LIVE, observation.imageSource)
        assertEquals(0.83, observation.imageScore!!, 0.0)
        assertTrue(registry.snapshot().isEmpty())
        assertNull(model.uiState.value.storedPhoto)
        model.goHome()
        assertFalse(container.cleanupPendingPhotos(UID))
        assertTrue(deleted.isEmpty())
    }

    @Test fun theSaveThatCompletesTheMissionCelebratesOnce() = runTest(dispatcher) {
        stored.value = listOf(MissionProgressTest.saved("a"), MissionProgressTest.saved("b"))
        scan(); runCurrent()
        model.confirmSelectedObservation(); runCurrent()
        assertTrue(model.uiState.value.celebrateMission)
        assertTrue(model.uiState.value.message.orEmpty().startsWith("Observation saved. Mission complete"))
        model.onMissionCelebrated()
        assertFalse(model.uiState.value.celebrateMission)
    }

    @Test fun saveBlocksDuplicateClicksAndNavigationUntilTheLocalWriteCompletes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        coEvery { records.save(any()) } coAnswers { saves++; gate.await(); persisted += firstArg<Observation>(); Unit }
        scan(); runCurrent()
        model.confirmSelectedObservation(); runCurrent()
        model.confirmSelectedObservation()
        model.goHome()
        assertTrue(model.uiState.value.isSaving)
        assertEquals(AppScreen.RESULTS, model.uiState.value.screen)
        assertTrue(registry.candidates(UID).isEmpty())
        assertEquals(1, saves)
        gate.complete(Unit); runCurrent()
        assertEquals(AppScreen.COLLECTION, model.uiState.value.screen)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun failedSaveRetainsPhotoForAnotherSaveOrLaterCleanup() = runTest(dispatcher) {
        coEvery { records.save(any()) } throws IOException("local write failed")
        scan(); runCurrent()
        model.confirmSelectedObservation(); runCurrent()
        assertFalse(model.uiState.value.isSaving)
        assertNotNull(model.uiState.value.storedPhoto)
        assertEquals(PendingPhotoState.ACTIVE, registry.snapshot().single().state)
        model.goToScan()
        assertFalse(container.cleanupPendingPhotos(UID))
        assertEquals(1, deleted.size)
    }

    @Test fun lateFailureFromAnOlderScanCannotOverwriteNewResults() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        firstClassificationGate = gate
        scan("first-camera.jpg"); runCurrent()
        model.goToScan()
        scan("second-camera.jpg"); runCurrent()
        val latest = model.uiState.value
        assertTrue(latest.photoPath!!.endsWith("second-camera.jpg"))
        gate.complete(Unit); runCurrent()
        assertEquals(latest.storedPhoto, model.uiState.value.storedPhoto)
        assertEquals(latest.imagePredictions, model.uiState.value.imagePredictions)
        assertNull(model.uiState.value.analysisError)
        assertFalse(model.uiState.value.message.orEmpty().contains("late old failure"))
    }

    @Test fun accountChangeQueuesOldPhotoWithoutDeletingItUnderAnotherAccount() = runTest(dispatcher) {
        scan(); runCurrent()
        authFlow.value = AuthState.Authenticated(UserProfile("another-user", null, null, false))
        runCurrent()
        assertEquals(AppScreen.HOME, model.uiState.value.screen)
        assertNull(model.uiState.value.storedPhoto)
        assertFalse(container.cleanupPendingPhotos(UID))
        assertEquals(1, registry.snapshot().size)
        assertTrue(deleted.isEmpty())
    }

    @Test fun lateAlaFailureCannotOverwriteTheNewScanContext() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), any()) } coAnswers {
            requests++
            if (requests == 1) withContext(NonCancellable) { gate.await(); throw IOException("old ALA failure") }
            NearbyContext(emptyMap(), ContextDataSource.ALA_LIVE, 8)
        }
        scan("first-context.jpg"); runCurrent()
        model.goToScan()
        scan("second-context.jpg"); runCurrent()
        val context = model.uiState.value.nearbyContext
        gate.complete(Unit); runCurrent()
        assertEquals(context, model.uiState.value.nearbyContext)
        assertEquals(ContextDataSource.ALA_LIVE, model.uiState.value.nearbyContext?.source)
        assertFalse(model.uiState.value.message.orEmpty().contains("old ALA failure"))
    }

    @Test fun missingApiKeyFailsBeforeUploading() = runTest(dispatcher) {
        every { container.isPlantNetConfigured } returns false
        scan(); runCurrent()
        assertEquals(0, uploads)
        assertEquals(0, downloads)
        assertTrue(model.uiState.value.analysisError.orEmpty().contains("PLANTNET_API_KEY"))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun guidedDemoDoesNotUploadOrDownload() = runTest(dispatcher) {
        model.runGuidedDemo(); runCurrent()
        assertEquals(0, uploads)
        assertEquals(0, downloads)
        assertEquals(listOf<String?>(null), classifiedPaths)
        assertEquals(ImageSource.DEMO_ADAPTER, model.uiState.value.imageSource)
    }

    private companion object { const val UID = "viewmodel-test-user" }
}
