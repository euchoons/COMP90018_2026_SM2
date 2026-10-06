package au.edu.unimelb.floraguide.ui

import androidx.lifecycle.ViewModelStore
import au.edu.unimelb.floraguide.data.photo.CapturedPhotoFiles
import au.edu.unimelb.floraguide.di.AppContainer
import au.edu.unimelb.floraguide.domain.model.AppScreen
import au.edu.unimelb.floraguide.domain.model.EvidenceBreakdown
import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.NetworkStatus
import au.edu.unimelb.floraguide.domain.repository.NetworkStatusProvider
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import au.edu.unimelb.floraguide.domain.repository.UserProfile
import au.edu.unimelb.floraguide.domain.usecase.IdentifyStoredPhotoUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real consent + ViewModel + upload use case, with fake network/storage boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UploadTimeoutViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val network = MutableStateFlow(NetworkStatus.UNAVAILABLE)
    private val profile = UserProfile("upload-user", null, null, true)
    private lateinit var auth: MutableStateFlow<AuthState>
    private lateinit var viewModel: FloraGuideViewModel
    private lateinit var owner: ViewModelStore
    private lateinit var photos: File
    private var uploadCalls = 0
    private var cancelledUploads = 0
    private var uploadDelay = 0L
    private var uploadError: Exception? = null
    private val species = Species("test", "Test plant", "Test species", emptySet(), emptyMap(), 0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        photos = temporary.newFolder("photos")
        auth = MutableStateFlow(AuthState.Authenticated(profile))
        val authRepository = mockk<AuthRepository>(relaxed = true)
        every { authRepository.authState } returns auth
        every { authRepository.getCurrentUser() } answers { (auth.value as? AuthState.Authenticated)?.user }
        val observations = mockk<ObservationRepository>(relaxed = true)
        every { observations.observeAll() } returns flowOf(emptyList())
        val networkProvider = object : NetworkStatusProvider {
            override fun currentStatus() = network.value
            override suspend fun awaitUsableNetwork() { network.first { it != NetworkStatus.UNAVAILABLE } }
        }
        val store = object : PhotoStore {
            override suspend fun uploadPhoto(localPath: String): StoredPhoto {
                uploadCalls++
                uploadError?.let { throw it }
                try {
                    networkProvider.awaitUsableNetwork()
                    delay(uploadDelay)
                    return StoredPhoto("plant_photos/test.jpg", "gs://test/plant_photos/test.jpg", 12,
                        "checksum", "image/jpeg")
                } catch (cancelled: CancellationException) {
                    cancelledUploads++
                    throw cancelled
                }
            }
            override suspend fun downloadPhoto(photo: StoredPhoto): File =
                temporary.newFile().apply { writeText("cloud copy") }
            override suspend fun deletePhoto(gsUri: String) = Unit
        }
        val classifier = object : ImageClassifier {
            override suspend fun classify(photoPath: String?) = ImageClassification(
                listOf(ImagePrediction(species, 0.8, 1)), ImageSource.PLANTNET_LIVE)
        }
        val ranking = listOf(RankedCandidate(species, 1.0, 1, 1, null,
            EvidenceBreakdown(0.8, 1.0, 1.0, 1.0)))
        val container = mockk<AppContainer>(relaxed = true)
        every { container.authRepository } returns authRepository
        every { container.observationRepository } returns observations
        every { container.networkStatus } returns networkProvider
        every { container.identifyStoredPhoto } returns IdentifyStoredPhotoUseCase(store, classifier)
        every { container.isPlantNetConfigured } returns true
        every { container.capturedPhotoFiles } returns CapturedPhotoFiles(photos)
        every { container.locationTracker.snapshotForObservation() } returns null
        every { container.rankCandidates.imageOnly(any()) } returns ranking
        every { container.rankCandidates.live(any(), any(), any(), any()) } returns ranking
        viewModel = FloraGuideViewModel(container)
        owner = ViewModelStore().also { it.put("timeout-test", viewModel) }
    }

    @After fun tearDown() {
        owner.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun capture(approve: Boolean = true): File {
        viewModel.goToScan()
        val id = requireNotNull(viewModel.beginCapture())
        val photo = File(photos, "observation-$id.jpg").apply { writeText("test photo") }
        viewModel.analyzeCapturedPhoto(photo.absolutePath, null)
        if (approve) viewModel.approvePhotoUpload(id)
        return photo
    }

    @Test fun timeoutStopsLoadingPreservesPhotoAndDoesNotDuplicateSnackbar() = runTest(dispatcher) {
        runCurrent()
        val photo = capture()
        runCurrent(); advanceTimeBy(29_999); runCurrent()
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertTrue(viewModel.uiState.value.isClassifying)
        advanceTimeBy(1); runCurrent()
        val state = viewModel.uiState.value
        assertEquals(UploadFailureKind.NO_INTERNET, state.uploadFailureDialog?.kind)
        assertFalse(state.isClassifying)
        assertFalse(state.isContextLoading)
        assertNull(state.identificationStage)
        assertNull(state.message)
        assertEquals(photo.absolutePath, state.photoPath)
        assertTrue(photo.exists())
        assertTrue(state.displayedRanking.isEmpty())
        assertEquals(1, cancelledUploads)
    }

    @Test fun closeKeepsThePhotoAndReconnectionDoesNotAutomaticallyRetry() = runTest(dispatcher) {
        runCurrent(); val photo = capture()
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        val failure = requireNotNull(viewModel.uiState.value.uploadFailureDialog)
        viewModel.dismissUploadFailure(failure.requestGeneration)
        network.value = NetworkStatus.AVAILABLE
        advanceTimeBy(60_000); runCurrent()
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertEquals(photo.absolutePath, viewModel.uiState.value.photoPath)
        assertEquals(1, uploadCalls)
        assertNotNull(viewModel.uiState.value.analysisError)
    }

    @Test fun retryGetsANewWindowAndDuplicateClicksCannotStartTwoUploads() = runTest(dispatcher) {
        runCurrent(); capture()
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        val first = requireNotNull(viewModel.uiState.value.uploadFailureDialog)
        viewModel.retryUploadAfterTimeout(first.requestGeneration)
        viewModel.retryUploadAfterTimeout(first.requestGeneration)
        runCurrent(); advanceTimeBy(29_999); runCurrent()
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertTrue(viewModel.uiState.value.isClassifying)
        advanceTimeBy(1); runCurrent()
        val second = requireNotNull(viewModel.uiState.value.uploadFailureDialog)
        assertNotEquals(first.requestGeneration, second.requestGeneration)
        assertEquals(2, uploadCalls)
        viewModel.dismissUploadFailure(first.requestGeneration)
        assertEquals(second, viewModel.uiState.value.uploadFailureDialog)
    }

    @Test fun manualRetryAfterReconnectionProducesTheExistingRanking() = runTest(dispatcher) {
        runCurrent(); val photo = capture()
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        val failure = requireNotNull(viewModel.uiState.value.uploadFailureDialog)
        network.value = NetworkStatus.AVAILABLE
        viewModel.retryUploadAfterTimeout(failure.requestGeneration)
        runCurrent()
        assertEquals(2, uploadCalls)
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertNull(viewModel.uiState.value.analysisError)
        assertEquals(photo.absolutePath, viewModel.uiState.value.photoPath)
        assertEquals(species, viewModel.uiState.value.selectedCandidate?.species)
    }

    @Test fun leavingDuringTheWaitDoesNotShowALateFailure() = runTest(dispatcher) {
        runCurrent(); capture()
        runCurrent(); advanceTimeBy(10_000)
        viewModel.goToScan()
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(AppScreen.SCAN, viewModel.uiState.value.screen)
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertFalse(viewModel.uiState.value.isClassifying)
        assertEquals(1, cancelledUploads)
    }

    @Test fun accountChangeInvalidatesAnOldFailureAndItsRetryButton() = runTest(dispatcher) {
        runCurrent(); capture()
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        val old = requireNotNull(viewModel.uiState.value.uploadFailureDialog)
        auth.value = AuthState.Authenticated(profile.copy(uid = "different-user"))
        runCurrent()
        viewModel.retryUploadAfterTimeout(old.requestGeneration)
        runCurrent()
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertEquals(1, uploadCalls)
    }

    @Test fun noTimerOrTransferStartsBeforeConsent() = runTest(dispatcher) {
        runCurrent(); capture(approve = false)
        advanceTimeBy(60_000); runCurrent()
        assertNotNull(viewModel.uiState.value.pendingPhotoConsent)
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertEquals(0, uploadCalls)
    }

    @Test fun slowOnlineUploadUsesTimeoutRatherThanNoInternetMessage() = runTest(dispatcher) {
        network.value = NetworkStatus.AVAILABLE
        uploadDelay = 60_000
        runCurrent(); capture()
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(UploadFailureKind.TIMEOUT, viewModel.uiState.value.uploadFailureDialog?.kind)
    }

    @Test fun unknownNetworkStatusIsNotReportedAsOffline() = runTest(dispatcher) {
        network.value = NetworkStatus.UNKNOWN
        uploadDelay = 60_000
        runCurrent(); capture()
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(UploadFailureKind.TIMEOUT, viewModel.uiState.value.uploadFailureDialog?.kind)
    }

    @Test fun explicitErrorsKeepTheirOriginalFailureHandling() = runTest(dispatcher) {
        uploadError = IOException("Upload permission denied")
        runCurrent(); capture(); runCurrent()
        assertNull(viewModel.uiState.value.uploadFailureDialog)
        assertTrue(viewModel.uiState.value.analysisError.orEmpty().contains("permission denied"))
        assertFalse(viewModel.uiState.value.isClassifying)
    }
}
