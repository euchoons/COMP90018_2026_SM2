package au.edu.unimelb.floraguide.di

import android.content.Context
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.data.ala.AlaOccurrenceClient
import au.edu.unimelb.floraguide.data.ala.ReliableAlaSpeciesContextRepository
import au.edu.unimelb.floraguide.data.catalog.FLOWERING_TABLE_ASSET
import au.edu.unimelb.floraguide.data.catalog.parseFloweringTable
import au.edu.unimelb.floraguide.data.firebase.FirebaseAuthRepository
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage
import au.edu.unimelb.floraguide.data.firebase.PendingPhotoUploads
import au.edu.unimelb.floraguide.data.local.FloraGuideDatabase
import au.edu.unimelb.floraguide.data.observation.OfflineFirstObservationRepository
import au.edu.unimelb.floraguide.data.photo.CapturedPhotoFiles
import au.edu.unimelb.floraguide.data.plantnet.PlantNetClient
import au.edu.unimelb.floraguide.data.plantnet.PlantNetImageClassifier
import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.NetworkStatusProvider
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.SpeciesContextRepository
import au.edu.unimelb.floraguide.domain.usecase.IdentifyStoredPhotoUseCase
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoCleanupUseCase
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import au.edu.unimelb.floraguide.platform.AndroidNetworkStatusProvider
import au.edu.unimelb.floraguide.platform.LocationTracker
import au.edu.unimelb.floraguide.platform.SensorMonitor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val capturedPhotoFiles = CapturedPhotoFiles(File(appContext.filesDir, "photos"))
    // Application-owned cleanup can finish even after the ViewModel is cleared.
    private val photoCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun discardUnsentPhoto(localPath: String, onFailure: () -> Unit = {}) {
        photoCleanupScope.launch {
            val removed = runCatching { capturedPhotoFiles.discardUnsent(localPath) }.getOrDefault(false)
            if (!removed) onFailure()
        }
    }

    val database: FloraGuideDatabase by lazy {
        FloraGuideDatabase.getInstance(appContext)
    }

    val authRepository: AuthRepository by lazy {
        FirebaseAuthRepository(context = appContext)
    }

    val isPlantNetConfigured: Boolean = BuildConfig.PLANTNET_API_KEY.isNotBlank()
    val imageClassifier: ImageClassifier = PlantNetImageClassifier(
        client = PlantNetClient(apiKey = BuildConfig.PLANTNET_API_KEY.trim()),
    )
    val pendingPhotos = PendingPhotoUploads.get(appContext)
    val networkStatus: NetworkStatusProvider = AndroidNetworkStatusProvider(appContext)
    val photoStorage: PhotoStore = FirebasePhotoStorage(
        appContext,
        pendingUploads = pendingPhotos,
        networkStatus = networkStatus,
    )
    val identifyStoredPhoto = IdentifyStoredPhotoUseCase(
        photoStorage,
        imageClassifier,
        onUndeliveredUpload = { pendingPhotos.abandon(it.gsUri) },
    )
    val cleanupPendingPhotos: PendingPhotoCleanupUseCase =
        PendingPhotoUploads.cleanup(appContext) { authRepository.getCurrentUser()?.uid }

    /** Real scans use the reliability-focused ALA adapter. */
    val speciesContextRepository: SpeciesContextRepository =
        ReliableAlaSpeciesContextRepository(AlaOccurrenceClient())

    val observationRepository: ObservationRepository = OfflineFirstObservationRepository(
        context = appContext,
        dao = database.observationDao(),
    )

    val rankCandidates = RankSpeciesCandidatesUseCase(
        floweringRecords = appContext.assets.open(FLOWERING_TABLE_ASSET).bufferedReader().use { parseFloweringTable(it.readText()) },
        outOfSeasonMultiplier = 0.50
    )
    val sensorMonitor = SensorMonitor(appContext)
    val locationTracker = LocationTracker(appContext)
}
