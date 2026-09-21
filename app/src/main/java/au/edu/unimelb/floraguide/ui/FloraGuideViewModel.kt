package au.edu.unimelb.floraguide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.edu.unimelb.floraguide.di.AppContainer
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import au.edu.unimelb.floraguide.domain.usecase.IdentificationStage
import au.edu.unimelb.floraguide.domain.model.AppScreen
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CONTEXT_RADIUS_KM = 8

/** One immutable state object makes loading, fallback and before/after ranking states explicit. */
data class FloraGuideUiState(
    val screen: AppScreen = AppScreen.HOME,
    val sensorSnapshot: SensorSnapshot = SensorSnapshot(),
    val location: GeoPoint = CAMPUS_DEMO_LOCATION,
    val usingDemoLocation: Boolean = true,
    val locationStatus: String = "University of Melbourne demo location",
    val selectedHabitat: Habitat = Habitat.TREE_CANOPY,
    val photoPath: String? = null,
    /** Heading when the photo was taken; the live heading has moved on by confirmation time. */
    val captureHeadingDegrees: Float? = null,
    val storedPhoto: StoredPhoto? = null,
    val identificationStage: IdentificationStage? = null,
    val analysisError: String? = null,
    val imagePredictions: List<ImagePrediction> = emptyList(),
    val imageSource: ImageSource? = null,
    val imageElapsedMillis: Long? = null,
    val imageOnlyRanking: List<RankedCandidate> = emptyList(),
    val fusedRanking: List<RankedCandidate> = emptyList(),
    val nearbyContext: NearbyContext? = null,
    val analysisDate: LocalDate = LocalDate.now(),
    val selectedSpeciesId: String? = null,
    val isClassifying: Boolean = false,
    val isContextLoading: Boolean = false,
    val observations: List<Observation> = emptyList(),
    val message: String? = null,
    val analysisPrefersLiveData: Boolean = true,
) {
    val displayedRanking: List<RankedCandidate>
        get() = if (fusedRanking.isNotEmpty()) fusedRanking else imageOnlyRanking

    val selectedCandidate: RankedCandidate?
        get() = displayedRanking.firstOrNull { it.species.id == selectedSpeciesId }
            ?: displayedRanking.firstOrNull()

    val uniqueSpeciesCount: Int
        get() = observations.map { it.species.id }.distinct().size
}

class FloraGuideViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private var analysisJob: Job? = null
    private val _uiState = MutableStateFlow(FloraGuideUiState())
    val uiState: StateFlow<FloraGuideUiState> = _uiState.asStateFlow()
    val authState: StateFlow<AuthState> = container.authRepository.authState

    private fun sessionKey(): String? = container.authRepository.getCurrentUser()?.uid
        ?: if (authState.value == AuthState.OfflineGuest) "anonymous_user" else null

    init {
        viewModelScope.launch {
            authState.map { sessionKey() }.distinctUntilChanged().collectLatest { uid ->
                analysisJob?.cancel()
                // Do not retain another account's photos, results or collection on screen.
                _uiState.update { FloraGuideUiState(sensorSnapshot = it.sensorSnapshot) }
                if (uid != null) {
                    try {
                        container.observationRepository.observeAll().collect { observations ->
                            if (sessionKey() == uid) _uiState.update { it.copy(observations = observations) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (sessionKey() == uid) showMessage("Could not load local observations. Please reopen the app.")
                    }
                }
            }
        }
        container.sensorMonitor.start { snapshot ->
            _uiState.update { it.copy(sensorSnapshot = snapshot) }
        }
    }

    fun signIn(email: String, pass: String) {
        viewModelScope.launch {
            container.authRepository.signInWithEmail(email, pass).onFailure { showMessage(it.message ?: "Sign-in failed.") }
        }
    }

    fun register(email: String, pass: String, name: String) {
        viewModelScope.launch {
            container.authRepository.registerWithEmail(email, pass, name).onFailure { showMessage(it.message ?: "Registration failed.") }
        }
    }

    fun signInAnonymously() {
        viewModelScope.launch {
            container.authRepository.signInAnonymously().onFailure { showMessage(it.message ?: "Guest sign-in failed. You can continue offline.") }
        }
    }

    fun continueOffline() {
        viewModelScope.launch { container.authRepository.continueOffline() }
    }

    fun importLocalObservations() = observationAction(
        "Local guest observations imported into this account.", container.observationRepository::importLocalObservations,
    )

    fun retrySync() = observationAction("Cloud sync queued.", container.observationRepository::retrySync)

    private fun observationAction(message: String, action: suspend () -> Unit) {
        val uid = sessionKey() ?: return
        viewModelScope.launch {
            if (sessionKey() != uid) return@launch
            try {
                action()
                if (sessionKey() == uid) showMessage(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sessionKey() == uid) showMessage("Could not complete this action. Please retry.")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { container.authRepository.signOut() }
    }

    private suspend fun fetchAndFuse(
        predictions: List<ImagePrediction>,
        preferLiveData: Boolean,
    ) {
        val uid = sessionKey()
        _uiState.update { it.copy(isContextLoading = true, message = null) }
        val current = _uiState.value
        try {
            val nearby = container.speciesContextRepository.nearbyOccurrenceCounts(
                candidates = predictions.map { it.species },
                location = current.location,
                radiusKm = CONTEXT_RADIUS_KM,
                preferLiveData = preferLiveData,
            )
            currentCoroutineContext().ensureActive()
            if (sessionKey() != uid) throw CancellationException("Account changed")
            val latest = _uiState.value
            val fused = container.rankCandidates(
                predictions = predictions,
                nearbyCounts = nearby.countsBySpeciesId,
                habitat = latest.selectedHabitat,
                date = latest.analysisDate,
            )
            _uiState.update {
                it.copy(
                    fusedRanking = fused,
                    nearbyContext = nearby,
                    selectedSpeciesId = fused.firstOrNull()?.species?.id,
                    isContextLoading = false,
                    message = nearby.warning,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isContextLoading = false,
                    message = error.message ?: "Context lookup failed.",
                )
            }
        }
    }
    fun goHome() {
        analysisJob?.cancel()
        _uiState.update {
            it.copy(
                screen = AppScreen.HOME,
                isClassifying = false,
                isContextLoading = false,
                message = null,
            )
        }
    }

    fun goToCollection() {
        _uiState.update { it.copy(screen = AppScreen.COLLECTION, message = null) }
    }

    fun goToAccount() {
        _uiState.update {
            it.copy(
                screen = AppScreen.ACCOUNT,
                message = null,
            )
        }
    }

    fun goToScan() {
        analysisJob?.cancel()
        _uiState.update {
            it.copy(
                screen = AppScreen.SCAN,
                message = null,
                photoPath = null,
                storedPhoto = null,
                identificationStage = null,
                analysisError = null,
                imagePredictions = emptyList(),
                imageSource = null,
                imageElapsedMillis = null,
                imageOnlyRanking = emptyList(),
                fusedRanking = emptyList(),
                nearbyContext = null,
                selectedSpeciesId = null,
                isClassifying = false,
                isContextLoading = false,
            )
        }
    }

    fun setHabitat(habitat: Habitat) {
        _uiState.update { current -> current.copy(selectedHabitat = habitat) }
        rerankWithCurrentContext()
    }

    fun selectSpecies(speciesId: String) {
        _uiState.update { it.copy(selectedSpeciesId = speciesId) }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) {
            useCampusDemoLocation("Location permission denied; campus demo coordinates are active.")
            return
        }
        container.locationTracker.start(
            onLocation = { point ->
                _uiState.update {
                    it.copy(
                        location = point,
                        usingDemoLocation = false,
                        locationStatus = buildString {
                            append("Live device location")
                            point.accuracyMetres?.let { accuracy -> append(" · ±${accuracy.toInt()} m") }
                        },
                    )
                }
            },
            onError = { reason -> useCampusDemoLocation(reason) },
        )
    }

    fun useCampusDemoLocation(reason: String? = null) {
        container.locationTracker.stop()
        _uiState.update {
            it.copy(
                location = CAMPUS_DEMO_LOCATION,
                usingDemoLocation = true,
                locationStatus = "University of Melbourne demo location",
                message = reason,
            )
        }
    }

    fun analyzeCapturedPhoto(photoPath: String?, captureHeadingDegrees: Float?) {
        if (photoPath.isNullOrBlank()) {
            showMessage("Capture a photo before starting identification.")
            return
        }
        startAnalysis(
            photoPath = photoPath,
            preferLiveData = true,
            analysisDate = LocalDate.now(),
            captureHeadingDegrees = captureHeadingDegrees,
        )
    }

    fun retryIdentification() {
        val current = _uiState.value
        if (current.isClassifying || current.photoPath == null) return
        startAnalysis(
            photoPath = current.photoPath,
            preferLiveData = true,
            analysisDate = current.analysisDate,
            // The phone has moved since the capture; keep the heading it was taken with.
            captureHeadingDegrees = current.captureHeadingDegrees,
            previouslyUploaded = current.storedPhoto,
        )
    }

    fun runGuidedDemo() {
        container.locationTracker.stop()
        _uiState.update {
            it.copy(
                location = CAMPUS_DEMO_LOCATION,
                usingDemoLocation = true,
                locationStatus = "Guided demo · University of Melbourne",
                selectedHabitat = Habitat.TREE_CANOPY,
            )
        }
        startAnalysis(
            photoPath = null,
            preferLiveData = false,
            analysisDate = GUIDED_DEMO_DATE,
            captureHeadingDegrees = null,
        )
    }

    fun retryContextLookup() {
        val current = _uiState.value
        if (current.imagePredictions.isEmpty()) return
        analysisJob?.cancel()
        _uiState.update { it.copy(analysisPrefersLiveData = true) }
        analysisJob = viewModelScope.launch {
            fetchAndFuse(
                predictions = current.imagePredictions,
                preferLiveData = true,
            )
        }
    }

    fun confirmSelectedObservation() {
        val uid = sessionKey() ?: return
        val current = _uiState.value
        val selected = current.selectedCandidate ?: return
        val contextSource = current.nearbyContext?.source ?: ContextDataSource.DEMO_FALLBACK
        val observation = Observation(
            id = UUID.randomUUID().toString(),
            species = selected.species,
            observedAt = Instant.now(),
            coarseLocation = current.location.coarsened(),
            habitat = current.selectedHabitat,
            photoPath = current.photoPath,
            cloudPhotoUri = current.storedPhoto?.gsUri,
            imageScore = current.imagePredictions.firstOrNull { it.species.id == selected.species.id }?.score,
            imageSource = current.imageSource,
            headingDegrees = current.captureHeadingDegrees,
            relativeScore = selected.relativeScore,
            contextSource = contextSource,
        )
        viewModelScope.launch {
            if (sessionKey() != uid) return@launch
            try {
                container.observationRepository.save(observation)
                if (sessionKey() == uid) _uiState.update {
                    it.copy(screen = AppScreen.COLLECTION, message = "Observation saved with a coarsened location.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sessionKey() == uid) showMessage("Could not save this observation. Please retry.")
            }
        }
    }

    fun deleteObservation(id: String) {
        val uid = sessionKey() ?: return
        viewModelScope.launch {
            if (sessionKey() != uid) return@launch
            try {
                container.observationRepository.delete(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sessionKey() == uid) showMessage("Could not delete this observation. Please retry.")
            }
        }
    }

    fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun startAnalysis(
        photoPath: String?,
        preferLiveData: Boolean,
        analysisDate: LocalDate,
        captureHeadingDegrees: Float?,
        previouslyUploaded: StoredPhoto? = null,
    ) {
        val uid = sessionKey() ?: return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            if (sessionKey() != uid) return@launch
            _uiState.update {
                it.copy(
                    screen = AppScreen.RESULTS,
                    photoPath = photoPath,
                    captureHeadingDegrees = captureHeadingDegrees,
                    storedPhoto = previouslyUploaded,
                    identificationStage = null,
                    analysisError = null,
                    imagePredictions = emptyList(),
                    imageSource = null,
                    imageElapsedMillis = null,
                    imageOnlyRanking = emptyList(),
                    fusedRanking = emptyList(),
                    nearbyContext = null,
                    analysisDate = analysisDate,
                    selectedSpeciesId = null,
                    isClassifying = true,
                    isContextLoading = false,
                    message = null,
                    analysisPrefersLiveData = preferLiveData,
                )
            }

            try {
                val classification = if (preferLiveData) {
                    check(container.isPlantNetConfigured) {
                        "Set PLANTNET_API_KEY in the root local.properties, then rebuild the app."
                    }
                    val localPath = requireNotNull(photoPath) { "No captured photo was provided." }
                    container.identifyStoredPhoto(
                        localPath = localPath,
                        previouslyUploaded = previouslyUploaded,
                        onUploaded = { stored ->
                            if (sessionKey() != uid) throw CancellationException("Account changed")
                            _uiState.update { it.copy(storedPhoto = stored) }
                        },
                        onStage = { stage ->
                            if (sessionKey() != uid) throw CancellationException("Account changed")
                            _uiState.update { it.copy(identificationStage = stage) }
                        },
                    )
                } else {
                    // Only an explicit guided-demo action may use demo predictions.
                    container.imageClassifier.classify(null)
                }
                currentCoroutineContext().ensureActive()
                if (sessionKey() != uid) throw CancellationException("Account changed")
                val predictions = classification.predictions
                val imageOnly = container.rankCandidates.imageOnly(predictions)
                _uiState.update {
                    it.copy(
                        imagePredictions = predictions,
                        identificationStage = null,
                        analysisError = null,
                        imageSource = classification.source,
                        imageElapsedMillis = classification.elapsedMillis,
                        imageOnlyRanking = imageOnly,
                        selectedSpeciesId = imageOnly.firstOrNull()?.species?.id,
                        isClassifying = false,
                        isContextLoading = true,
                    )
                }
                fetchAndFuse(predictions, preferLiveData)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isClassifying = false,
                        isContextLoading = false,
                        message = error.message ?: "Analysis failed.",
                        analysisError = error.message ?: "Analysis failed.",
                        identificationStage = null,
                    )
                }
            }
        }
    }

    private fun rerankWithCurrentContext() {
        val current = _uiState.value
        val context = current.nearbyContext ?: return
        if (current.imagePredictions.isEmpty()) return
        val fused = container.rankCandidates(
            predictions = current.imagePredictions,
            nearbyCounts = context.countsBySpeciesId,
            habitat = current.selectedHabitat,
            date = current.analysisDate,
        )
        _uiState.update {
            it.copy(
                fusedRanking = fused,
                selectedSpeciesId = fused.firstOrNull()?.species?.id,
            )
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        container.sensorMonitor.stop()
        container.locationTracker.stop()
        super.onCleared()
    }

    class Factory(
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FloraGuideViewModel::class.java))
            return FloraGuideViewModel(container) as T
        }
    }
}

private fun GeoPoint.coarsened(): GeoPoint = GeoPoint(
    latitude = round(latitude * 1_000.0) / 1_000.0,
    longitude = round(longitude * 1_000.0) / 1_000.0,
    accuracyMetres = null,
)

val CAMPUS_DEMO_LOCATION = GeoPoint(
    latitude = -37.7963,
    longitude = 144.9614,
)

private val GUIDED_DEMO_DATE: LocalDate = LocalDate.of(2026, 8, 17)
