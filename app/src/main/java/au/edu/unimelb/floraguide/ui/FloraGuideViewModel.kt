package au.edu.unimelb.floraguide.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.edu.unimelb.floraguide.di.AppContainer
import au.edu.unimelb.floraguide.domain.model.AppScreen
import au.edu.unimelb.floraguide.domain.model.CaptureLocationSource
import au.edu.unimelb.floraguide.domain.model.CaptureSnapshot
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import au.edu.unimelb.floraguide.domain.usecase.CreateObservationUseCase
import au.edu.unimelb.floraguide.domain.usecase.IdentificationStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
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
private const val MAX_ALA_CANDIDATES = 5

/** One immutable state object makes loading, fallback and before/after ranking states explicit. */
data class FloraGuideUiState(
    val screen: AppScreen = AppScreen.HOME,
    val sensorSnapshot: SensorSnapshot = SensorSnapshot(),
    val location: GeoPoint = CAMPUS_DEMO_LOCATION,
    val usingDemoLocation: Boolean = true,
    val locationStatus: String = "Location not yet available; enable it before capture",
    /** Explicit "Skip location" for this scan; survives Activity recreation, unlike Compose effects. */
    val locationSkipped: Boolean = false,
    val selectedHabitat: Habitat = Habitat.TREE_CANOPY,
    val photoPath: String? = null,
    val storedPhoto: StoredPhoto? = null,
    val capture: CaptureSnapshot? = null,
    val identificationStage: IdentificationStage? = null,
    val analysisError: String? = null,
    val imagePredictions: List<ImagePrediction> = emptyList(),
    val imageSource: ImageSource? = null,
    val imageElapsedMillis: Long? = null,
    val imageOnlyRanking: List<RankedCandidate> = emptyList(),
    val fusedRanking: List<RankedCandidate> = emptyList(),
    val nearbyContext: NearbyContext? = null,
    val analysisDate: LocalDate = LocalDate.now(),
    /** Null follows the top suggestion; an explicit choice survives reranking. */
    val selectedSpeciesId: String? = null,
    val isClassifying: Boolean = false,
    val isContextLoading: Boolean = false,
    val isSaving: Boolean = false,
    val observations: List<Observation> = emptyList(),
    val message: String? = null,
    val analysisPrefersLiveData: Boolean = true,
) {
    val displayedRanking: List<RankedCandidate>
        get() = fusedRanking.ifEmpty { imageOnlyRanking }

    val selectedCandidate: RankedCandidate?
        get() = displayedRanking.firstOrNull { it.species.id == selectedSpeciesId }
            ?: displayedRanking.firstOrNull()
    val captureHeadingDegrees: Float?
        get() = capture?.headingDegrees

    val uniqueSpeciesCount: Int
        get() = observations
            .filter { it.imageSource != ImageSource.DEMO_ADAPTER }
            .map { it.species.id }
            .distinct()
            .size

    val canSave: Boolean
        get() = selectedCandidate != null &&
            capture?.location != null &&
            !isClassifying && !isContextLoading && !isSaving
}

class FloraGuideViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private var analysisJob: Job? = null
    private val observationFactory = CreateObservationUseCase()
    private val _uiState = MutableStateFlow(FloraGuideUiState())
    val uiState: StateFlow<FloraGuideUiState> = _uiState.asStateFlow()
    val authState: StateFlow<AuthState> = container.authRepository.authState

    private fun sessionKey(): String? = container.authRepository.getCurrentUser()?.uid
        ?: if (authState.value == AuthState.OfflineGuest) "anonymous_user" else null

    init {
        viewModelScope.launch {
            authState.map { sessionKey() }.distinctUntilChanged().collectLatest { uid ->
                analysisJob?.cancel()
                _uiState.update { current -> FloraGuideUiState(sensorSnapshot = current.sensorSnapshot) }
                if (uid != null) {
                    try {
                        container.observationRepository.observeAll().collect { observations ->
                            if (sessionKey() == uid) {
                                _uiState.update { it.copy(observations = observations) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (sessionKey() == uid) {
                            showMessage("Could not load local observations. Please reopen the app.")
                        }
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
            container.authRepository.signInWithEmail(email, pass)
                .onFailure { showMessage(it.message ?: "Sign-in failed.") }
        }
    }

    fun register(email: String, pass: String, name: String) {
        viewModelScope.launch {
            container.authRepository.registerWithEmail(email, pass, name)
                .onFailure { showMessage(it.message ?: "Registration failed.") }
        }
    }

    fun signInAnonymously() {
        viewModelScope.launch {
            container.authRepository.signInAnonymously()
                .onFailure { showMessage(it.message ?: "Guest sign-in failed. You can continue offline.") }
        }
    }

    fun continueOffline() {
        viewModelScope.launch { container.authRepository.continueOffline() }
    }

    fun importLocalObservations() = observationAction(
        "Local guest observations imported into this account.",
        container.observationRepository::importLocalObservations,
    )

    fun retrySync() = observationAction(
        "Cloud sync queued.",
        container.observationRepository::retrySync,
    )

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

    fun goHome() {
        analysisJob?.cancel()
        container.locationTracker.stop()
        _uiState.update {
            it.copy(
                screen = AppScreen.HOME,
                isClassifying = false,
                isContextLoading = false,
                isSaving = false,
                message = null,
            )
        }
    }

    fun goToCollection() {
        analysisJob?.cancel()
        container.locationTracker.stop()
        _uiState.update {
            it.copy(
                screen = AppScreen.COLLECTION,
                isClassifying = false,
                isContextLoading = false,
                isSaving = false,
                message = null,
            )
        }
    }

    fun goToAccount() {
        analysisJob?.cancel()
        container.locationTracker.stop()
        _uiState.update { it.copy(screen = AppScreen.ACCOUNT, message = null) }
    }

    fun goToScan() {
        analysisJob?.cancel()
        container.locationTracker.stop()
        _uiState.update { current ->
            FloraGuideUiState(
                screen = AppScreen.SCAN,
                sensorSnapshot = current.sensorSnapshot,
                selectedHabitat = current.selectedHabitat,
                observations = current.observations,
            )
        }
    }

    fun setHabitat(habitat: Habitat) {
        _uiState.update { it.copy(selectedHabitat = habitat) }
        rerankWithCurrentContext()
    }

    fun selectSpecies(speciesId: String) {
        _uiState.update { it.copy(selectedSpeciesId = speciesId) }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) {
            skipLocation("Location permission denied. Identification can continue, but ALA will be skipped.")
            return
        }
        _uiState.update { it.copy(locationSkipped = false, locationStatus = "Waiting for a recent device location...") }
        container.locationTracker.start(
            onLocation = { point ->
                _uiState.update {
                    it.copy(
                        location = point,
                        usingDemoLocation = false,
                        locationStatus = buildString {
                            append("Device location ready")
                            point.accuracyMetres?.let { accuracy -> append(" · ±${accuracy.toInt()} m") }
                        },
                    )
                }
            },
            onError = { reason -> skipLocation(reason) },
        )
    }

    /** Retains the historical callback name used by the UI; live scans no longer substitute demo coordinates. */
    fun useCampusDemoLocation(reason: String? = null) {
        skipLocation(reason)
        _uiState.update { it.copy(locationSkipped = true) }
    }

    private fun skipLocation(reason: String? = null) {
        container.locationTracker.stop()
        _uiState.update {
            it.copy(
                usingDemoLocation = true,
                locationStatus = reason ?: "Location skipped for this live scan. ALA will not be queried.",
                message = reason,
            )
        }
    }

    fun analyzeCapturedPhoto(photoPath: String?, captureHeadingDegrees: Float?) {
        if (photoPath.isNullOrBlank()) {
            showMessage("Capture a photo before starting identification.")
            return
        }
        val location = container.locationTracker.snapshotForObservation()
        val capture = CaptureSnapshot(
            observationId = UUID.randomUUID().toString(),
            capturedAt = Instant.now(),
            location = location,
            locationSource = if (location != null) {
                CaptureLocationSource.DEVICE
            } else {
                CaptureLocationSource.UNAVAILABLE
            },
            headingDegrees = captureHeadingDegrees,
        )
        container.locationTracker.stop()
        startAnalysis(
            photoPath = photoPath,
            preferLiveData = true,
            capture = capture,
        )
    }

    fun retryIdentification() {
        val current = _uiState.value
        val capture = current.capture ?: return
        if (current.isClassifying || current.isSaving || current.photoPath == null) return
        startAnalysis(
            photoPath = current.photoPath,
            preferLiveData = true,
            capture = capture,
            previouslyUploaded = current.storedPhoto,
        )
    }

    fun runGuidedDemo() {
        container.locationTracker.stop()
        val capture = CaptureSnapshot(
            observationId = UUID.randomUUID().toString(),
            capturedAt = GUIDED_DEMO_DATE.atStartOfDay(ZoneId.systemDefault()).toInstant(),
            location = CAMPUS_DEMO_LOCATION,
            locationSource = CaptureLocationSource.GUIDED_DEMO,
            headingDegrees = null,
        )
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
            capture = capture,
        )
    }

    fun retryContextLookup() {
        val current = _uiState.value
        val capture = current.capture ?: return
        if (
            current.imagePredictions.isEmpty() || current.isContextLoading || current.isSaving ||
            !current.analysisPrefersLiveData
        ) return
        if (capture.location == null) {
            showMessage("This photo has no usable capture location. Enable location and take a new photo.")
            return
        }
        current.nearbyContext?.retryNotBefore?.let { deadline ->
            if (Instant.now().isBefore(deadline)) {
                showMessage("ALA requested a pause. Retry after $deadline.")
                return
            }
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            fetchAndFuse(
                predictions = current.imagePredictions,
                preferLiveData = true,
                capture = capture,
            )
        }
    }

    fun confirmSelectedObservation() {
        val uid = sessionKey() ?: return
        val current = _uiState.value
        val capture = current.capture ?: return
        val selected = current.selectedCandidate ?: return
        if (capture.location == null) {
            showMessage("A usable capture location is required before saving this observation. Retake with location enabled.")
            return
        }

        val observation = runCatching {
            observationFactory(
                capture = capture,
                selected = selected,
                ranking = current.displayedRanking,
                habitat = current.selectedHabitat,
                photoPath = current.photoPath,
                cloudPhotoUri = current.storedPhoto?.gsUri,
                imageSource = current.imageSource,
                context = current.nearbyContext,
                confirmedAt = Instant.now(),
            )
        }.getOrElse {
            showMessage(it.message ?: "Could not prepare this observation.")
            return
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            if (sessionKey() != uid) return@launch
            try {
                container.observationRepository.save(observation)
                if (sessionKey() == uid) {
                    _uiState.update {
                        it.copy(
                            screen = AppScreen.COLLECTION,
                            isSaving = false,
                            message = "Observation saved using the capture-time location.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sessionKey() == uid) {
                    _uiState.update { it.copy(isSaving = false) }
                    showMessage("Could not save this observation. Please retry.")
                }
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
        capture: CaptureSnapshot,
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
                    storedPhoto = previouslyUploaded,
                    capture = capture,
                    identificationStage = null,
                    analysisError = null,
                    imagePredictions = emptyList(),
                    imageSource = null,
                    imageElapsedMillis = null,
                    imageOnlyRanking = emptyList(),
                    fusedRanking = emptyList(),
                    nearbyContext = null,
                    analysisDate = capture.capturedAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                    selectedSpeciesId = null,
                    isClassifying = true,
                    isContextLoading = false,
                    isSaving = false,
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
                    container.imageClassifier.classify(null)
                }
                currentCoroutineContext().ensureActive()
                if (sessionKey() != uid) throw CancellationException("Account changed")

                // Rank, display and save the same candidates that are checked against ALA.
                val predictions = classification.predictions.take(MAX_ALA_CANDIDATES)
                val imageOnly = container.rankCandidates.imageOnly(predictions)
                _uiState.update {
                    it.copy(
                        imagePredictions = predictions,
                        identificationStage = null,
                        analysisError = null,
                        imageSource = classification.source,
                        imageElapsedMillis = classification.elapsedMillis,
                        imageOnlyRanking = imageOnly,
                        fusedRanking = imageOnly,
                        isClassifying = false,
                        isContextLoading = true,
                    )
                }
                fetchAndFuse(predictions, preferLiveData, capture)
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

    private suspend fun fetchAndFuse(
        predictions: List<ImagePrediction>,
        preferLiveData: Boolean,
        capture: CaptureSnapshot,
    ) {
        val uid = sessionKey()
        _uiState.update { it.copy(isContextLoading = true, message = null) }
        val candidates = predictions.take(MAX_ALA_CANDIDATES)

        try {
            val nearby = when {
                !preferLiveData -> container.speciesContextRepository.nearbyOccurrenceCounts(
                    candidates = candidates.map { it.species },
                    location = requireNotNull(capture.location),
                    radiusKm = CONTEXT_RADIUS_KM,
                    preferLiveData = false,
                )
                capture.location == null -> NearbyContext(
                    countsBySpeciesId = emptyMap(),
                    source = ContextDataSource.NOT_REQUESTED,
                    radiusKm = CONTEXT_RADIUS_KM,
                    warning = "No usable capture location. ALA was not queried; image-only ranking is retained.",
                )
                else -> container.speciesContextRepository.nearbyOccurrenceCounts(
                    candidates = candidates.map { it.species },
                    location = capture.location,
                    radiusKm = CONTEXT_RADIUS_KM,
                    preferLiveData = true,
                )
            }

            currentCoroutineContext().ensureActive()
            if (sessionKey() != uid) throw CancellationException("Account changed")
            val latest = _uiState.value
            val fused = if (!preferLiveData) {
                container.rankCandidates(
                    predictions = candidates,
                    nearbyCounts = nearby.countsBySpeciesId,
                    habitat = latest.selectedHabitat,
                    date = latest.analysisDate,
                )
            } else {
                container.rankCandidates.live(candidates, nearby)
            }

            _uiState.update {
                it.copy(
                    fusedRanking = fused,
                    nearbyContext = nearby,
                    isContextLoading = false,
                    message = nearby.warning,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val imageOnly = container.rankCandidates.imageOnly(candidates)
            _uiState.update {
                it.copy(
                    fusedRanking = imageOnly,
                    isContextLoading = false,
                    nearbyContext = NearbyContext(
                        countsBySpeciesId = emptyMap(),
                        source = ContextDataSource.ALA_UNAVAILABLE,
                        radiusKm = CONTEXT_RADIUS_KM,
                        warning = "ALA lookup failed. Image-only ranking is retained.",
                    ),
                    message = error.message ?: "Context lookup failed.",
                )
            }
        }
    }

    private fun rerankWithCurrentContext() {
        val current = _uiState.value
        val context = current.nearbyContext ?: return
        if (current.imagePredictions.isEmpty() || current.isContextLoading) return
        val candidates = current.imagePredictions.take(MAX_ALA_CANDIDATES)
        val fused = if (current.imageSource == ImageSource.DEMO_ADAPTER) {
            container.rankCandidates(
                predictions = candidates,
                nearbyCounts = context.countsBySpeciesId,
                habitat = current.selectedHabitat,
                date = current.analysisDate,
            )
        } else {
            container.rankCandidates.live(candidates, context)
        }
        _uiState.update {
            it.copy(fusedRanking = fused)
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

val CAMPUS_DEMO_LOCATION = GeoPoint(
    latitude = -37.7963,
    longitude = 144.9614,
)

private val GUIDED_DEMO_DATE: LocalDate = LocalDate.of(2026, 8, 17)
