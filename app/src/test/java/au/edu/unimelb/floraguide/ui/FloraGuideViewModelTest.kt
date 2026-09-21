package au.edu.unimelb.floraguide.ui

import au.edu.unimelb.floraguide.di.AppContainer
import au.edu.unimelb.floraguide.domain.model.*
import au.edu.unimelb.floraguide.domain.repository.*
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FloraGuideViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val container = mockk<AppContainer>(relaxed = true)
    private val auth = mockk<AuthRepository>(relaxed = true)
    private val records = mockk<ObservationRepository>()
    private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { container.authRepository } returns auth
        every { container.observationRepository } returns records
        every { auth.authState } returns state
        every { auth.getCurrentUser() } answers { (state.value as? AuthState.Authenticated)?.user }
        every { records.observeAll() } returns MutableStateFlow(emptyList())
    }
    @After fun close() { Dispatchers.resetMain() }

    @Test fun `capture heading survives later sensor readings and retries including null`() = runTest(dispatcher) {
        state.value = AuthState.OfflineGuest
        val sensorListener = slot<(SensorSnapshot) -> Unit>()
        every { container.sensorMonitor.start(capture(sensorListener)) } just Runs
        val model = FloraGuideViewModel(container)
        runCurrent()
        for (capturedHeading in listOf(90f, null)) {
            // Simulate movement between the shutter and the JPEG-saved callback.
            sensorListener.captured(SensorSnapshot(headingDegrees = 180f))
            model.analyzeCapturedPhoto("/capture.jpg", capturedHeading)
            sensorListener.captured(SensorSnapshot(headingDegrees = 270f))
            runCurrent()
            assertEquals(capturedHeading, model.uiState.value.captureHeadingDegrees)
            model.retryIdentification()
            runCurrent()
            assertEquals(capturedHeading, model.uiState.value.captureHeadingDegrees)
        }
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    @Test fun `account changes cancel old observation subscriptions and clear analysis`() = runTest(dispatcher) {
        val subscribed = mutableListOf<String>()
        val closed = mutableListOf<String>()
        every { records.observeAll() } answers {
            val uid = auth.getCurrentUser()!!.uid
            flow {
                subscribed += uid
                try { emit(emptyList()); awaitCancellation() } finally { closed += uid }
            }
        }
        state.value = AuthState.Authenticated(UserProfile("A", null, null, false))
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.goToScan()
        state.value = AuthState.Authenticated(UserProfile("B", null, null, false))
        runCurrent()
        assertEquals(listOf("A", "B"), subscribed)
        assertEquals(listOf("A"), closed)
        assertEquals(AppScreen.HOME, model.uiState.value.screen)
        assertNull(model.uiState.value.photoPath)
        assertTrue(model.uiState.value.observations.isEmpty())
        state.value = AuthState.Unauthenticated
        runCurrent()
        assertEquals(listOf("A", "B"), closed)
    }

    @Test fun `offline guided demo preserves candidate IDs and does not request live context`() = runTest(dispatcher) {
        state.value = AuthState.OfflineGuest
        val species = Species("stable-id", "Blackwood", "Acacia melanoxylon", setOf(8), mapOf(Habitat.TREE_CANOPY to 1.0), 10)
        every { container.rankCandidates } returns RankSpeciesCandidatesUseCase()
        coEvery { container.imageClassifier.classify(null) } returns ImageClassification(
            listOf(ImagePrediction(species, 0.8, 1)), ImageSource.DEMO_ADAPTER,
        )
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), false) } returns
            NearbyContext(mapOf("stable-id" to 10), ContextDataSource.DEMO_FALLBACK, 8)
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.runGuidedDemo()
        runCurrent()
        assertEquals("stable-id", model.uiState.value.fusedRanking.single().species.id)
        assertEquals(10, model.uiState.value.fusedRanking.single().nearbyRecordCount)
        coVerify(exactly = 1) { container.speciesContextRepository.nearbyOccurrenceCounts(listOf(species), any(), 8, false) }
        model.retryContextLookup()
        runCurrent()
        val contextRepository = container.speciesContextRepository
        coVerify(exactly = 0) { contextRepository.nearbyOccurrenceCounts(any(), any(), any(), true) }
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    @Test fun `live capture without usable location skips ALA and cannot save demo coordinates`() = runTest(dispatcher) {
        val predictions = prepareLiveIdentification()
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.onLocationPermissionResult(false)
        model.analyzeCapturedPhoto("/capture.jpg", null)
        runCurrent()
        val result = model.uiState.value
        assertEquals(ContextDataSource.UNAVAILABLE, result.nearbyContext?.source)
        assertEquals(container.rankCandidates.imageOnly(predictions), result.fusedRanking)
        coVerify(exactly = 0) { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), any()) }
        model.clearMessage()
        assertNotNull(model.uiState.value.nearbyContext?.warning)
        model.confirmSelectedObservation()
        runCurrent()
        coVerify(exactly = 0) { records.save(any()) }
        assertTrue(model.uiState.value.message.orEmpty().contains("no usable location"))
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    @Test fun `unavailable stale and inaccurate device locations do not become evidence`() = runTest(dispatcher) {
        prepareLiveIdentification()
        val onLocation = slot<(GeoPoint) -> Unit>()
        val onError = slot<(String) -> Unit>()
        every { container.locationTracker.start(capture(onLocation), capture(onError)) } just Runs
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.onLocationPermissionResult(true)
        val now = System.currentTimeMillis()
        for (point in listOf(null, GeoPoint(-37.8, 145.0, 50f, now - 180_000), GeoPoint(-37.8, 145.0, 2_000f, now))) {
            if (point != null) onLocation.captured(point) else onError.captured("No location fix")
            model.analyzeCapturedPhoto("/capture.jpg", null)
            runCurrent()
            assertNull(model.uiState.value.analysisLocation)
            assertEquals(ContextDataSource.UNAVAILABLE, model.uiState.value.nearbyContext?.source)
        }
        coVerify(exactly = 0) { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), any()) }
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    @Test fun `location is frozen before upload and reused for context retries and saving`() = runTest(dispatcher) {
        val predictions = prepareLiveIdentification()
        val onLocation = slot<(GeoPoint) -> Unit>()
        every { container.locationTracker.start(capture(onLocation), any()) } just Runs
        val captured = GeoPoint(-37.8, 145.0, 20f, System.currentTimeMillis())
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), captured, 8, true) } returns
            NearbyContext(mapOf("live" to 0), ContextDataSource.ALA_LIVE, 8)
        coEvery { records.save(any()) } just Runs
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.onLocationPermissionResult(true)
        onLocation.captured(captured)
        model.analyzeCapturedPhoto("/capture.jpg", null)
        onLocation.captured(captured.copy(latitude = -38.0))
        runCurrent()
        assertEquals(captured, model.uiState.value.analysisLocation)
        assertEquals(predictions.single().species.id, model.uiState.value.fusedRanking.single().species.id)
        model.retryContextLookup()
        runCurrent()
        coVerify(exactly = 2) { container.speciesContextRepository.nearbyOccurrenceCounts(any(), captured, 8, true) }
        model.confirmSelectedObservation()
        runCurrent()
        coVerify { records.save(match { it.coarseLocation.latitude == captured.latitude }) }
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    private fun prepareLiveIdentification(): List<ImagePrediction> {
        state.value = AuthState.OfflineGuest
        val species = Species("live", "Plant", "Test plant", emptySet(), emptyMap(), 0)
        val predictions = listOf(ImagePrediction(species, 0.8, 1))
        every { container.rankCandidates } returns RankSpeciesCandidatesUseCase()
        every { container.isPlantNetConfigured } returns true
        coEvery { container.identifyStoredPhoto.invoke(any(), any(), any(), any()) } returns
            ImageClassification(predictions, ImageSource.PLANTNET_LIVE)
        return predictions
    }

    @Test fun `failed retry replaces old geographic evidence and habitat edits preserve warning`() = runTest(dispatcher) {
        val predictions = prepareLiveIdentification()
        val onLocation = slot<(GeoPoint) -> Unit>()
        every { container.locationTracker.start(capture(onLocation), any()) } just Runs
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), 8, true) } returns
            NearbyContext(mapOf("live" to 99), ContextDataSource.ALA_LIVE, 8)
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.onLocationPermissionResult(true)
        onLocation.captured(GeoPoint(-37.8, 145.0, 20f, System.currentTimeMillis()))
        model.analyzeCapturedPhoto("/capture.jpg", null)
        runCurrent()
        assertTrue(model.uiState.value.fusedRanking.single().evidence.locationUsed)
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), 8, true) } throws java.io.IOException()
        model.retryContextLookup()
        runCurrent()
        assertEquals(container.rankCandidates.imageOnly(predictions), model.uiState.value.fusedRanking)
        val warning = model.uiState.value.nearbyContext?.warning
        assertNotNull(warning)
        model.clearMessage()
        model.setHabitat(Habitat.LAWN)
        assertEquals(warning, model.uiState.value.nearbyContext?.warning)
        assertFalse(model.uiState.value.fusedRanking.single().evidence.locationUsed)
        state.value = AuthState.Unauthenticated
        runCurrent()
    }
}
