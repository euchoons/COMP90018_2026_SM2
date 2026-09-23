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
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    @Test fun `habitat change keeps an explicit species choice while auto selection follows the top`() = runTest(dispatcher) {
        state.value = AuthState.OfflineGuest
        val canopy = Species("canopy", "Blackwood", "Acacia melanoxylon", emptySet(), mapOf(Habitat.TREE_CANOPY to 1.0), 0)
        val lawn = Species("lawn", "Kidney weed", "Dichondra repens", emptySet(), mapOf(Habitat.LAWN to 1.0), 0)
        every { container.rankCandidates } returns RankSpeciesCandidatesUseCase()
        coEvery { container.imageClassifier.classify(null) } returns ImageClassification(
            listOf(ImagePrediction(canopy, 0.5, 1), ImagePrediction(lawn, 0.5, 2)), ImageSource.DEMO_ADAPTER,
        )
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), false) } returns
            NearbyContext(emptyMap(), ContextDataSource.DEMO_FALLBACK, 8)
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.runGuidedDemo()
        runCurrent()
        assertEquals("canopy", model.uiState.value.selectedCandidate?.species?.id)
        model.setHabitat(Habitat.LAWN)
        assertEquals("lawn", model.uiState.value.selectedCandidate?.species?.id)
        model.selectSpecies("canopy")
        model.setHabitat(Habitat.LAWN)
        assertEquals("lawn", model.uiState.value.displayedRanking.first().species.id)
        assertEquals("canopy", model.uiState.value.selectedCandidate?.species?.id)
        state.value = AuthState.Unauthenticated
        runCurrent()
    }

    @Test fun `image-only and final rankings contain the same candidates`() = runTest(dispatcher) {
        state.value = AuthState.OfflineGuest
        val species = (1..8).map { Species("s$it", "Plant $it", "Genus species$it", emptySet(), emptyMap(), 0) }
        every { container.rankCandidates } returns RankSpeciesCandidatesUseCase()
        coEvery { container.imageClassifier.classify(null) } returns ImageClassification(
            species.mapIndexed { index, item -> ImagePrediction(item, 0.9 - index * 0.1, index + 1) },
            ImageSource.DEMO_ADAPTER,
        )
        coEvery { container.speciesContextRepository.nearbyOccurrenceCounts(any(), any(), any(), false) } returns
            NearbyContext(emptyMap(), ContextDataSource.DEMO_FALLBACK, 8)
        val model = FloraGuideViewModel(container)
        runCurrent()
        model.runGuidedDemo()
        runCurrent()
        val imageOnly = model.uiState.value.imageOnlyRanking.map { it.species.id }
        assertEquals(5, imageOnly.size)
        assertEquals(imageOnly.toSet(), model.uiState.value.fusedRanking.map { it.species.id }.toSet())
        state.value = AuthState.Unauthenticated
        runCurrent()
    }
}
