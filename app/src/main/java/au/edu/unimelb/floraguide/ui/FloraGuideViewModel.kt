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
import au.edu.unimelb.floraguide.domain.model.PredictedOrgan
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import au.edu.unimelb.floraguide.domain.privacy.PendingPhotoConsent
import au.edu.unimelb.floraguide.domain.privacy.PhotoConsentGate
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import au.edu.unimelb.floraguide.domain.usecase.CreateObservationUseCase
import au.edu.unimelb.floraguide.domain.usecase.IdentificationStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CONTEXT_RADIUS_KM = 8
private const val MAX_ALA_CANDIDATES = 5
private const val PENDING_CLEANUP_TIMEOUT_MS = 20_000L
private const val PENDING_CLEANUP_POLL_MS = 1_000L

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
    /** A device preference, so it outlives scans and account changes; it resets only on app restart. */
    val stabilityGateEnabled: Boolean = true,
    val photoPath: String? = null,
    val pendingPhotoConsent: PendingPhotoConsent? = null,
    val storedPhoto: StoredPhoto? = null,
    val capture: CaptureSnapshot? = null,
    val identificationStage: IdentificationStage? = null,
    val analysisError: String? = null,
    val imagePredictions: List<ImagePrediction> = emptyList(),
    val imageSource: ImageSource? = null,
    val imageElapsedMillis: Long? = null,
    val predictedOrgan: PredictedOrgan? = null,
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

        get() = screen == AppScreen.RESULTS &&
            pendingPhotoConsent == null &&
            selectedCandidate != null &&
            capture?.location != null &&
            // A fix blurred to about 2 km can inform ALA, but would pin the plant in the wrong place.
            capture?.locationSource != CaptureLocationSource.APPROXIMATE &&
            !isClassifying &&
            !isContextLoading &&
            !isSaving
}

class FloraGuideViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private var analysisJob: Job? = null
    private var requestGeneration = 0L
    private var accountChangeInProgress = false
    private var pendingCapture: CaptureSnapshot? = null
    private var pendingCaptureOwner: String? = null
    private var disposed = false
    private val photoConsentGate = PhotoConsentGate()
    private val observationFactory = CreateObservationUseCase()
    private val _uiState = MutableStateFlow(FloraGuideUiState())
    val uiState: StateFlow<FloraGuideUiState> = _uiState.asStateFlow()
    val authState: StateFlow<AuthState> = container.authRepository.authState

    private fun sessionKey(): String? = container.authRepository.getCurrentUser()?.uid
        ?: if (authState.value == AuthState.OfflineGuest) "anonymous_user" else null

    private fun isCurrentRequest(generation: Long, uid: String?): Boolean =
        requestGeneration == generation && sessionKey() == uid

    /** Invalidate callbacks BEFORE cancellation, and release only the old session's photo. */
    private fun abandonAnalysis(keepPhoto: StoredPhoto? = null) {
        requestGeneration++
        analysisJob?.cancel()
        analysisJob = null
        val old = _uiState.value.storedPhoto
        if (old != null && old.gsUri != keepPhoto?.gsUri) container.pendingPhotos.abandon(old.gsUri)
        pendingCapture = null
        _uiState.update {
            it.copy(storedPhoto = keepPhoto, identificationStage = null, isClassifying = false, isContextLoading = false)
        }
    }

    private fun canChangeAnalysis(): Boolean {
        if (_uiState.value.isSaving || accountChangeInProgress) {
            showMessage("Finish saving or the account change before starting another action.")
            return false
        }
        return true
    }

    init {
        viewModelScope.launch {
            authState.map { sessionKey() }.distinctUntilChanged().collectLatest { uid ->
                abandonAnalysis()
                discardPendingConsent()

                _uiState.update { current ->
                    FloraGuideUiState(
                        sensorSnapshot = current.sensorSnapshot,
                        stabilityGateEnabled = current.stabilityGateEnabled,
                    )
                }

                if (uid != null) {
                    if (uid != "anonymous_user") {
                        container.pendingPhotos.resumeCleanup(uid)
                    }

                    try {
                        container.observationRepository.observeAll().collect { observations ->
                            if (sessionKey() == uid) {
                                _uiState.update {
                                    it.copy(observations = observations)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        if (sessionKey() == uid) {
                            runCatching { android.util.Log.e("FloraGuideVM", "Failed to load local observations: ${e.localizedMessage}", e) }
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
        if (!canChangeAnalysis()) return
        viewModelScope.launch {
            container.authRepository.signInWithEmail(email, pass)
                .onFailure { showMessage(it.message ?: "Sign-in failed.") }
        }
    }

    fun register(email: String, pass: String, name: String) {
        if (!canChangeAnalysis()) return
        viewModelScope.launch {
            container.authRepository.registerWithEmail(email, pass, name)
                .onFailure { showMessage(it.message ?: "Registration failed.") }
        }
    }

    fun signInAnonymously() {
        if (!canChangeAnalysis()) return
        viewModelScope.launch {
            container.authRepository.signInAnonymously()
                .onFailure { showMessage(it.message ?: "Guest sign-in failed. You can continue offline.") }
        }
    }

    fun continueOffline() {
        if (!canChangeAnalysis()) return
        abandonAnalysis()
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
        if (!canChangeAnalysis()) return
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
    if (!canChangeAnalysis()) return

    // A cloud guest can never sign back in, so its queued scan photos cannot be cleaned later.
    val guestUid = container.authRepository.getCurrentUser()
        ?.takeIf { it.isAnonymous }
        ?.uid

    abandonAnalysis()
    discardPendingConsent()

    if (guestUid == null) {
        viewModelScope.launch {
            container.authRepository.signOut()
        }
        return
    }

    accountChangeInProgress = true
    showMessage("Removing this guest's pending scan photos before signing out...")

    viewModelScope.launch {
        try {
            drainPendingPhotos(guestUid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort: the guest account is abandoned either way.
        } finally {
            accountChangeInProgress = false
        }

        container.authRepository.signOut()
    }
}

    fun goHome() {
        if (!canChangeAnalysis()) return

        abandonAnalysis()
        discardPendingConsent()

        container.locationTracker.stop()

        _uiState.update {
            it.copy(
                screen = AppScreen.HOME,
                photoPath = null,
                isSaving = false,
                message = null,
            )
        }
    }

    fun goToCollection() {
        if (!canChangeAnalysis()) return

        abandonAnalysis()
        discardPendingConsent()

        container.locationTracker.stop()

        _uiState.update {
            it.copy(
                screen = AppScreen.COLLECTION,
                photoPath = null,
                isSaving = false,
                message = null,
            )
        }
    }

    fun goToAccount() {
        if (!canChangeAnalysis()) return

        abandonAnalysis()
        discardPendingConsent()

        container.locationTracker.stop()
        _uiState.update {
            it.copy(
                screen = AppScreen.ACCOUNT,
                photoPath = null,
                message = null,
            )
        }
    }

    fun goToScan() {
        if (!canChangeAnalysis()) return

        // Re-tapping Observe must not drop a shutter press whose JPEG is still being saved.
        val shutterCapture = pendingCapture
        val shutterOwner = pendingCaptureOwner

        abandonAnalysis()
        discardPendingConsent()

        // Restore an in-progress shutter capture only if it still belongs to the current account.
        if (shutterCapture != null &&
            shutterOwner != null &&
            shutterOwner == sessionKey()
        ){
            pendingCapture = shutterCapture
            pendingCaptureOwner = shutterOwner
        }

        container.locationTracker.stop()

        _uiState.update { current ->
            FloraGuideUiState(
                screen = AppScreen.SCAN,
                sensorSnapshot = current.sensorSnapshot,
                selectedHabitat = current.selectedHabitat,
                stabilityGateEnabled = current.stabilityGateEnabled,
                observations = current.observations,
            )
        }
    }

    fun setHabitat(habitat: Habitat) {
        _uiState.update { it.copy(selectedHabitat = habitat) }
        rerankWithCurrentContext()
    }

    fun setStabilityGateEnabled(enabled: Boolean) {
        _uiState.update { it.copy(stabilityGateEnabled = enabled) }
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
                val approximate = container.locationTracker.isApproximate()
                _uiState.update {
                    it.copy(
                        location = point,
                        usingDemoLocation = false,
                        locationStatus = buildString {
                            append(if (approximate) "Approximate location ready" else "Device location ready")
                            point.accuracyMetres?.let { accuracy -> append(" · ±${accuracy.toInt()} m") }
                        },
                    )
                }
            },
            onError = { reason -> skipLocation(reason) },
            onStale = {
                _uiState.update {
                    it.copy(locationStatus = "Waiting for a new device location. A capture now would skip ALA.")
                }
            },
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

    /**
     * Called at the shutter press. Saving the JPEG can take seconds, long enough for a fix near
     * the 60 s freshness limit to expire, so time and location are frozen here like the heading.
     */

    fun beginCapture(): String? {
        if (!canChangeAnalysis()) return null

        val current = _uiState.value
        if (
            disposed ||
            current.screen != AppScreen.SCAN ||
            current.pendingPhotoConsent != null
        ) {
            return null
        }

        val owner = sessionKey() ?: return null
        val capture = liveCaptureNow()

        pendingCapture = capture
        pendingCaptureOwner = owner

        return capture.observationId
    }

    /** Historical callback name retained: receiving a JPEG now stages consent, not analysis. */
    fun analyzeCapturedPhoto(photoPath: String?, captureHeadingDegrees: Float?) {
        if (photoPath.isNullOrBlank()) {
            showMessage("Capture a photo before starting identification.")
            return
        }

        if (!canChangeAnalysis()) {
            discardUnapprovedPhoto(photoPath)
            return
        }

        val pending = pendingCapture
        val owner = sessionKey()

        if (
            disposed ||
            pending == null ||
            owner == null ||
            pendingCaptureOwner != owner ||
            _uiState.value.screen != AppScreen.SCAN ||
            !container.capturedPhotoFiles.belongsToCapture(
                photoPath,
                pending.observationId,
            )
        ) {
            // A CameraX callback can arrive after navigation, sign-out or another shutter press.
            discardUnapprovedPhoto(photoPath)
            return
        }

        pendingCapture = null
        pendingCaptureOwner = null
        container.locationTracker.stop()

        val capture = pending.copy(
            headingDegrees = captureHeadingDegrees,
        )

        val request = PendingPhotoConsent(
            capture.observationId,
            owner,
            photoPath,
        )

        photoConsentGate.offer(request)

        _uiState.update {
            it.copy(
                photoPath = photoPath,
                capture = capture,
                pendingPhotoConsent = request,
                storedPhoto = null,
                analysisError = null,
                identificationStage = null,
                imagePredictions = emptyList(),
                imageSource = null,
                imageOnlyRanking = emptyList(),
                fusedRanking = emptyList(),
                nearbyContext = null,
                selectedSpeciesId = null,
                isClassifying = false,
                isContextLoading = false,
                isSaving = false,
                message = null,
            )
        }
    }

    fun approvePhotoUpload(captureId: String) {
        val owner = sessionKey() ?: return
        val current = _uiState.value
        val capture = current.capture ?: return

        if (
            disposed ||
            current.screen != AppScreen.SCAN ||
            capture.observationId != captureId
        ) {
            return
        }

        val request = photoConsentGate.approve(captureId, owner) ?: return

        _uiState.update {
            it.copy(
                pendingPhotoConsent = null,
                screen = AppScreen.RESULTS,
            )
        }

        startAnalysis(
            photoPath = request.photoPath,
            preferLiveData = true,
            capture = capture,
        )
    }

    fun cancelPhotoUpload(captureId: String) {
        if (_uiState.value.pendingPhotoConsent?.captureId != captureId) return

        discardPendingConsent()

        _uiState.update {
            it.copy(
                message = "Photo not sent. Take another photo or use the offline guided demo.",
            )
        }
    }

    private fun discardPendingConsent() {
        pendingCapture = null
        pendingCaptureOwner = null

        val unsent = photoConsentGate.reset()

        _uiState.update {
            it.copy(
                pendingPhotoConsent = null,
                photoPath = if (unsent != null) null else it.photoPath,
                capture = if (unsent != null) null else it.capture,
            )
        }

        unsent?.let {
            discardUnapprovedPhoto(it.photoPath)
        }
    }

    private fun discardUnapprovedPhoto(photoPath: String) {
        // Ignore duplicate callbacks for pending/accepted files, including a file already saved.
        if (
            photoConsentGate.wasEverApproved(photoPath) ||
            _uiState.value.pendingPhotoConsent?.photoPath == photoPath
        ) {
            return
        }

        val owner = sessionKey()

        container.discardUnsentPhoto(photoPath) {
            viewModelScope.launch {
                if (!disposed && sessionKey() == owner) {
                    showMessage(
                        "The photo was not sent, but its local file could not be removed."
                    )
                }
            }
        }
    }

    private fun liveCaptureNow(): CaptureSnapshot {
        val location = container.locationTracker.snapshotForObservation()
        return CaptureSnapshot(
            observationId = UUID.randomUUID().toString(),
            capturedAt = Instant.now(),
            location = location,
            locationSource = when {
                location == null -> CaptureLocationSource.UNAVAILABLE
                container.locationTracker.isApproximate() -> CaptureLocationSource.APPROXIMATE
                else -> CaptureLocationSource.DEVICE
            },
            headingDegrees = null,
        )
    }

    fun retryIdentification() {
        val current = _uiState.value
        val capture = current.capture ?: return

        if (
            current.screen != AppScreen.RESULTS ||
            current.isClassifying ||
            current.isContextLoading ||
            current.isSaving ||
            current.photoPath == null ||
            accountChangeInProgress
        ) {
            return
        }

        val owner = sessionKey() ?: return
        val photoPath = current.photoPath ?: return

        if (!photoConsentGate.isApproved(
                capture.observationId,
                owner,
                photoPath,
            )
        ) {
            showMessage("Agree to online identification before retrying this photo.")
            return
        }

        startAnalysis(
            photoPath = photoPath,
            preferLiveData = true,
            capture = capture,
            previouslyUploaded = current.storedPhoto,
        )
    }

    fun runGuidedDemo() {
        if (!canChangeAnalysis()) return

        discardPendingConsent()

        container.locationTracker.stop()

        val capture = CaptureSnapshot(
            observationId = UUID.randomUUID().toString(),
            capturedAt = GUIDED_DEMO_DATE
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant(),
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
            current.screen != AppScreen.RESULTS ||
            current.imagePredictions.isEmpty() ||
            current.isContextLoading ||
            current.isClassifying ||
            current.isSaving ||
            !current.analysisPrefersLiveData ||
            accountChangeInProgress
        ) {
            return
        }

        val owner = sessionKey() ?: return
        val path = current.photoPath ?: return

        if (!photoConsentGate.isApproved(
                capture.observationId,
                owner,
                path,
            )
        ) {
            return
        }

        if (capture.location == null) {
            showMessage(
                "This photo has no usable capture location. Enable location and take a new photo."
            )
            return
        }

        current.nearbyContext?.retryNotBefore?.let { deadline ->
            if (Instant.now().isBefore(deadline)) {
                showMessage(
                    "ALA requested a pause. Retry after ${LOCAL_TIME_FORMAT.format(deadline)}."
                )
                return
            }
        }

        val uid = sessionKey() ?: return

        requestGeneration++
        val generation = requestGeneration

        analysisJob?.cancel()

        _uiState.update {
            it.copy(isContextLoading = true)
        }

        analysisJob = viewModelScope.launch {
            fetchAndFuse(
                current.imagePredictions,
                true,
                capture,
                generation,
                uid,
            )
        }
    }

    fun confirmSelectedObservation() {
        val uid = sessionKey() ?: return
        val current = _uiState.value
        if (!current.canSave || accountChangeInProgress) return
        val capture = current.capture ?: return
        val selected = current.selectedCandidate ?: return
        if (capture.location == null) {
            showMessage("A usable capture location is required before saving this observation. Retake with location enabled.")
            return
        }
        if (capture.locationSource == CaptureLocationSource.APPROXIMATE) {
            showMessage("Saving needs precise location. Tap Use precise location on Observe, then take a new photo.")
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
        val photoUri = current.storedPhoto?.gsUri
        try {
            container.pendingPhotos.beginSave(photoUri)
        } catch (error: Exception) {
            showMessage(error.message ?: "Could not protect this photo for saving. Please retry.")
            return
        }
        val generation = requestGeneration
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var saved = false
            try {
                if (!isCurrentRequest(generation, uid)) return@launch
                container.observationRepository.save(observation)
                saved = true
                container.pendingPhotos.saved(photoUri)
                if (isCurrentRequest(generation, uid)) {
                    _uiState.update {
                        it.copy(
                            screen = AppScreen.COLLECTION,
                            storedPhoto = null,
                            photoPath = null,
                            isSaving = false,
                            message = "Observation saved using the capture-time location.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                runCatching { android.util.Log.e("FloraGuideVM", "Failed to save observation: ${e.localizedMessage}", e) }
                if (isCurrentRequest(generation, uid)) showMessage("Could not save this observation. Please retry.")
            } finally {
                if (!saved) container.pendingPhotos.saveFailed(photoUri)
                if (isCurrentRequest(generation, uid)) _uiState.update { it.copy(isSaving = false) }
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

    fun deleteAccount() {
        if (!canChangeAnalysis()) return
        val uid = sessionKey() ?: return
        val oldAnalysis = analysisJob
        abandonAnalysis()
        accountChangeInProgress = true
        showMessage("Removing pending scan photos before deleting the account...")
        viewModelScope.launch {
            try {
                oldAnalysis?.join()
                if (sessionKey() != uid) return@launch
                // Deleting Auth first would leave this device's journalled scan photos undeletable.
                if (!drainPendingPhotos(uid)) {
                    showMessage("Pending photo cleanup has not finished. Reconnect and retry account deletion.")
                    return@launch
                }
                if (sessionKey() != uid) return@launch
                container.authRepository.deleteAccount()
                    .onSuccess { if (sessionKey() == uid) clearMessage() }
                    .onFailure { error ->
                        if (sessionKey() == uid) showMessage(error.message ?: "Could not delete account. You may need to sign in again to verify your credentials.")
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sessionKey() == uid) showMessage("Could not clean up pending photos. Reconnect and retry account deletion.")
            } finally {
                accountChangeInProgress = false
            }
        }
    }

    /**
     * True once no journalled scan photo of [uid] remains. Bounded, because offline each Storage
     * delete would otherwise wait out the SDK's multi-minute retry window while the UI is locked.
     */
    private suspend fun drainPendingPhotos(uid: String): Boolean = withTimeoutOrNull(PENDING_CLEANUP_TIMEOUT_MS) {
        // A background cleanup's claim or a cancelling upload clears within seconds.
        while (container.cleanupPendingPhotos(uid)) delay(PENDING_CLEANUP_POLL_MS)
    } != null

    private fun startAnalysis(
        photoPath: String?,
        preferLiveData: Boolean,
        capture: CaptureSnapshot,
        previouslyUploaded: StoredPhoto? = null,
    ) {
        val uid = sessionKey() ?: return

        // Live identification is only allowed after the user has approved
        // sending this specific captured photo.
        if (
            preferLiveData &&
            (
                photoPath == null ||
                    !photoConsentGate.isApproved(
                        capture.observationId,
                        uid,
                        photoPath,
                    )
                )
        ) {
            showMessage("Agree to online identification before sending this photo.")
            return
        }

        // Invalidate any previous analysis request while preserving an already
        // uploaded photo when this is a retry.
        abandonAnalysis(keepPhoto = previouslyUploaded)

        val generation = requestGeneration

        // Set the busy state synchronously so another action cannot start
        // before the coroutine is dispatched.
        _uiState.update {
            it.copy(
                screen = AppScreen.RESULTS,
                photoPath = photoPath,
                pendingPhotoConsent = null,
                storedPhoto = previouslyUploaded,
                capture = capture,
                identificationStage = null,
                analysisError = null,
                imagePredictions = emptyList(),
                imageSource = null,
                imageElapsedMillis = null,
                predictedOrgan = null,
                imageOnlyRanking = emptyList(),
                fusedRanking = emptyList(),
                nearbyContext = null,
                analysisDate = capture.capturedAt
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate(),
                selectedSpeciesId = null,
                isClassifying = true,
                isContextLoading = false,
                isSaving = false,
                message = null,
                analysisPrefersLiveData = preferLiveData,
            )
        }

        analysisJob = viewModelScope.launch {
            if (!isCurrentRequest(generation, uid)) {
                return@launch
            }

            try {
                val classification = if (preferLiveData) {
                    check(container.isPlantNetConfigured) {
                        "Set PLANTNET_API_KEY in the root local.properties, then rebuild the app."
                    }

                    val localPath = requireNotNull(photoPath) {
                        "No captured photo was provided."
                    }

                    currentCoroutineContext().ensureActive()

                    check(
                        photoConsentGate.isApproved(
                            capture.observationId,
                            uid,
                            localPath,
                        )
                    ) {
                        "Photo consent is no longer valid. Take a new photo."
                    }

                    container.identifyStoredPhoto(
                        localPath = localPath,
                        previouslyUploaded = previouslyUploaded,

                        onUploaded = { stored ->
                            val captureChanged =
                                _uiState.value.capture?.observationId != capture.observationId

                            if (
                                !isCurrentRequest(generation, uid) ||
                                captureChanged
                            ) {
                                // The upload may finish after the user navigates away,
                                // signs out, retries, or starts another capture.
                                if (_uiState.value.storedPhoto?.gsUri != stored.gsUri) {
                                    container.pendingPhotos.abandon(stored.gsUri)
                                }

                                throw CancellationException(
                                    "Analysis session changed"
                                )
                            }

                            _uiState.update {
                                it.copy(storedPhoto = stored)
                            }
                        },

                        onStage = { stage ->
                            val captureChanged =
                                _uiState.value.capture?.observationId != capture.observationId

                            if (
                                !isCurrentRequest(generation, uid) ||
                                captureChanged
                            ) {
                                throw CancellationException(
                                    "Analysis session changed"
                                )
                            }

                            _uiState.update {
                                it.copy(identificationStage = stage)
                            }
                        },
                    )
                } else {
                    container.imageClassifier.classify(null)
                }

                currentCoroutineContext().ensureActive()

                if (
                    !isCurrentRequest(generation, uid) ||
                    _uiState.value.capture?.observationId != capture.observationId
                ) {
                    throw CancellationException(
                        "Analysis session changed"
                    )
                }

                val predictions =
                    classification.predictions.take(MAX_ALA_CANDIDATES)

                val imageOnly =
                    container.rankCandidates.imageOnly(predictions)

                _uiState.update {
                    it.copy(
                        imagePredictions = predictions,
                        identificationStage = null,
                        analysisError = null,
                        imageSource = classification.source,
                        imageElapsedMillis = classification.elapsedMillis,
                        predictedOrgan = classification.predictedOrgan,
                        imageOnlyRanking = imageOnly,
                        fusedRanking = imageOnly,
                        isClassifying = false,
                        isContextLoading = true,
                    )
                }

                fetchAndFuse(
                    predictions = predictions,
                    preferLiveData = preferLiveData,
                    capture = capture,
                    generation = generation,
                    uid = uid,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()

                if (
                    !isCurrentRequest(generation, uid) ||
                    _uiState.value.capture?.observationId != capture.observationId
                ) {
                    return@launch
                }

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
        generation: Long,
        uid: String,
    ) {
        currentCoroutineContext().ensureActive()

        if (!isCurrentRequest(generation, uid)) {
            throw CancellationException(
                "Analysis session changed"
            )
        }

        _uiState.update {
            it.copy(
                isContextLoading = true,
                message = null,
            )
        }

        val candidates =
            predictions.take(MAX_ALA_CANDIDATES)

        try {
            val nearby = when {
                !preferLiveData -> {
                    container.speciesContextRepository.nearbyOccurrenceCounts(
                        candidates = candidates.map { it.species },
                        location = requireNotNull(capture.location),
                        radiusKm = CONTEXT_RADIUS_KM,
                        preferLiveData = false,
                    )
                }

                capture.location == null -> {
                    NearbyContext(
                        countsBySpeciesId = emptyMap(),
                        source = ContextDataSource.NOT_REQUESTED,
                        radiusKm = CONTEXT_RADIUS_KM,
                        warning =
                            "No usable capture location. ALA was not queried, so no geographic adjustment is applied.",
                    )
                }

                else -> {
                    container.speciesContextRepository.nearbyOccurrenceCounts(
                        candidates = candidates.map { it.species },
                        location = capture.location,
                        radiusKm = CONTEXT_RADIUS_KM,
                        preferLiveData = true,
                    )
                }
            }

            currentCoroutineContext().ensureActive()

            if (
                !isCurrentRequest(generation, uid) ||
                _uiState.value.capture?.observationId != capture.observationId
            ) {
                throw CancellationException(
                    "Analysis session changed"
                )
            }

            val latest = _uiState.value

            val fused = if (!preferLiveData) {
                container.rankCandidates(
                    predictions = candidates,
                    nearbyCounts = nearby.countsBySpeciesId,
                    habitat = latest.selectedHabitat,
                    date = latest.analysisDate,
                )
            } else {
                container.rankCandidates.live(
                    candidates,
                    nearby,
                    latest.analysisDate.monthValue,
                    latest.predictedOrgan,
                )
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
            currentCoroutineContext().ensureActive()

            if (
                !isCurrentRequest(generation, uid) ||
                _uiState.value.capture?.observationId != capture.observationId
            ) {
                return
            }

            val unavailable = NearbyContext(
                countsBySpeciesId = emptyMap(),
                source = ContextDataSource.ALA_UNAVAILABLE,
                radiusKm = CONTEXT_RADIUS_KM,
                warning =
                    "ALA lookup failed, so no geographic adjustment is applied.",
            )

            val latest = _uiState.value

            // Keep the existing flowering cue when ALA fails.
            val fused = if (preferLiveData) {
                container.rankCandidates.live(
                    candidates,
                    unavailable,
                    latest.analysisDate.monthValue,
                    latest.predictedOrgan,
                )
            } else {
                container.rankCandidates.imageOnly(candidates)
            }

            _uiState.update {
                it.copy(
                    fusedRanking = fused,
                    isContextLoading = false,
                    nearbyContext = unavailable,
                    message = error.message ?: "Context lookup failed.",
                )
            }
        }
    }

    private fun rerankWithCurrentContext() {
        val current = _uiState.value
        val context = current.nearbyContext ?: return

        if (
            current.imagePredictions.isEmpty() ||
            current.isContextLoading
        ) {
            return
        }

        val candidates =
            current.imagePredictions.take(MAX_ALA_CANDIDATES)

        val fused =
            if (current.imageSource == ImageSource.DEMO_ADAPTER) {
                container.rankCandidates(
                    predictions = candidates,
                    nearbyCounts = context.countsBySpeciesId,
                    habitat = current.selectedHabitat,
                    date = current.analysisDate,
                )
            } else {
                container.rankCandidates.live(
                    candidates,
                    context,
                    current.analysisDate.monthValue,
                    current.predictedOrgan,
                )
            }

        _uiState.update {
            it.copy(fusedRanking = fused)
        }
    }

    override fun onCleared() {
        disposed = true

        abandonAnalysis()
        discardPendingConsent()

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

    private val GUIDED_DEMO_DATE: LocalDate =
        LocalDate.of(2026, 8, 17)

    /** Instants are UTC; users see capture and retry times in the device's zone. */
    internal val LOCAL_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern(
                "d MMM, h:mm:ss a",
                Locale.ENGLISH,
            )
            .withZone(ZoneId.systemDefault())
