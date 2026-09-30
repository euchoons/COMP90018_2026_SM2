package au.edu.unimelb.floraguide.ui

import androidx.lifecycle.ViewModelStore
import au.edu.unimelb.floraguide.data.photo.CapturedPhotoFiles
import au.edu.unimelb.floraguide.di.AppContainer
import au.edu.unimelb.floraguide.domain.model.AppScreen
import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.UserProfile
import au.edu.unimelb.floraguide.domain.usecase.IdentifyStoredPhotoUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FloraGuidePhotoConsentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val profile = UserProfile("user-a", null, null, true)
    private lateinit var auth: MutableStateFlow<AuthState>
    private lateinit var container: AppContainer
    private lateinit var identifier: IdentifyStoredPhotoUseCase
    private lateinit var observations: ObservationRepository
    private lateinit var viewModel: FloraGuideViewModel
    private lateinit var photos: File
    private lateinit var owner: ViewModelStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        photos = temporary.newFolder("photos")
        auth = MutableStateFlow(AuthState.Authenticated(profile))
        val authRepository = mockk<AuthRepository>(relaxed = true)
        every { authRepository.authState } returns auth
        every { authRepository.getCurrentUser() } answers {
            (auth.value as? AuthState.Authenticated)?.user
        }
        observations = mockk(relaxed = true)
        coEvery { observations.observeAll() } returns flowOf(emptyList())
        identifier = mockk()
        // The test stops at the pipeline boundary; no Firebase/API credentials are used.
        coEvery { identifier.invoke(any(), any(), any(), any()) } throws
            IllegalStateException("Test transport boundary reached")
        container = mockk(relaxed = true)
        every { container.authRepository } returns authRepository
        every { container.observationRepository } returns observations
        every { container.identifyStoredPhoto } returns identifier
        every { container.isPlantNetConfigured } returns true
        every { container.capturedPhotoFiles } returns CapturedPhotoFiles(photos)
        every { container.locationTracker.snapshotForObservation() } returns null
        viewModel = FloraGuideViewModel(container)
        owner = ViewModelStore().also { it.put("consent-test", viewModel) }
    }

    @After fun tearDown() {
        owner.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun capture(): Pair<String, File> {
        viewModel.goToScan()
        val id = requireNotNull(viewModel.beginCapture())
        val file = File(photos, "observation-$id.jpg").apply { writeText("test photo") }
        viewModel.analyzeCapturedPhoto(file.absolutePath, 45f)
        return id to file
    }

    @Test fun cameraCallbackAndRetryDoNotStartTransferBeforeConsent() = runTest(dispatcher) {
        runCurrent()
        capture()
        viewModel.retryIdentification()
        viewModel.retryContextLookup()
        viewModel.confirmSelectedObservation()
        runCurrent()
        assertNotNull(viewModel.uiState.value.pendingPhotoConsent)
        assertFalse(viewModel.uiState.value.isClassifying)
        coVerify(exactly = 0) { identifier.invoke(any(), any(), any(), any()) }
        coVerify(exactly = 0) { observations.save(any()) }
    }

    @Test fun agreementStartsExactlyOnePipelineAndDoesNotSave() = runTest(dispatcher) {
        runCurrent()
        val (id, file) = capture()
        viewModel.approvePhotoUpload(id)
        viewModel.approvePhotoUpload(id)
        runCurrent()
        assertNull(viewModel.uiState.value.pendingPhotoConsent)
        coVerify(exactly = 1) { identifier.invoke(file.absolutePath, any(), any(), any()) }
        coVerify(exactly = 0) { observations.save(any()) }
    }

    @Test fun cancellationQueuesOnlyTheUnsentLocalPhoto() = runTest(dispatcher) {
        runCurrent()
        val (id, file) = capture()
        viewModel.cancelPhotoUpload(id)
        viewModel.approvePhotoUpload(id)
        runCurrent()
        assertNull(viewModel.uiState.value.pendingPhotoConsent)
        assertNull(viewModel.uiState.value.photoPath)
        assertEquals(AppScreen.SCAN, viewModel.uiState.value.screen)
        verify(exactly = 1) { container.discardUnsentPhoto(file.absolutePath, any()) }
        coVerify(exactly = 0) { identifier.invoke(any(), any(), any(), any()) }
        coVerify(exactly = 0) { container.photoStorage.deletePhoto(any()) }
    }

    @Test fun navigationAndLateCameraCallbackNeverUpload() = runTest(dispatcher) {
        runCurrent()
        viewModel.goToScan()
        val id = requireNotNull(viewModel.beginCapture())
        val file = File(photos, "observation-$id.jpg").apply { writeText("test photo") }
        viewModel.goHome()
        viewModel.analyzeCapturedPhoto(file.absolutePath, null)
        runCurrent()
        assertEquals(AppScreen.HOME, viewModel.uiState.value.screen)
        assertNull(viewModel.uiState.value.pendingPhotoConsent)
        verify(exactly = 1) { container.discardUnsentPhoto(file.absolutePath, any()) }
        coVerify(exactly = 0) { identifier.invoke(any(), any(), any(), any()) }
    }

    @Test fun accountChangeInvalidatesTheOldDialog() = runTest(dispatcher) {
        runCurrent()
        val (id, file) = capture()
        auth.value = AuthState.Authenticated(profile.copy(uid = "user-b"))
        runCurrent()
        viewModel.approvePhotoUpload(id)
        runCurrent()
        assertNull(viewModel.uiState.value.pendingPhotoConsent)
        verify(exactly = 1) { container.discardUnsentPhoto(file.absolutePath, any()) }
        coVerify(exactly = 0) { identifier.invoke(any(), any(), any(), any()) }
    }

    @Test fun retryAfterAgreementReusesConsentButAnotherCaptureDoesNot() = runTest(dispatcher) {
        runCurrent()
        val (id, file) = capture()
        viewModel.approvePhotoUpload(id)
        runCurrent()
        viewModel.retryIdentification()
        runCurrent()
        coVerify(exactly = 2) { identifier.invoke(file.absolutePath, any(), any(), any()) }
        capture()
        viewModel.retryIdentification()
        runCurrent()
        assertNotNull(viewModel.uiState.value.pendingPhotoConsent)
        coVerify(exactly = 2) { identifier.invoke(any(), any(), any(), any()) }
    }

    @Test fun acceptedPhotoIsNotPassedToTheUnsentCleaner() = runTest(dispatcher) {
        runCurrent()
        val (id, file) = capture()
        viewModel.approvePhotoUpload(id)
        runCurrent()
        viewModel.goHome()
        // Simulate a duplicate delayed CameraX callback after navigation.
        viewModel.analyzeCapturedPhoto(file.absolutePath, null)
        runCurrent()
        verify(exactly = 0) { container.discardUnsentPhoto(file.absolutePath, any()) }
    }

    @Test fun guidedDemoDoesNotInvokeTheCloudIdentificationPipeline() = runTest(dispatcher) {
        runCurrent()
        coEvery { container.imageClassifier.classify(null) } throws CancellationException("Demo seam visited")
        viewModel.runGuidedDemo()
        runCurrent()
        coVerify(exactly = 1) { container.imageClassifier.classify(null) }
        coVerify(exactly = 0) { identifier.invoke(any(), any(), any(), any()) }
    }
}
