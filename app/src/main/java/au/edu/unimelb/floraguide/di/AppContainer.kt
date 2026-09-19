package au.edu.unimelb.floraguide.di

import android.content.Context
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.data.ala.AlaOccurrenceClient
import au.edu.unimelb.floraguide.data.ala.AlaSpeciesContextRepository
import au.edu.unimelb.floraguide.data.firebase.FirebaseAuthRepository
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage
import au.edu.unimelb.floraguide.data.local.FloraGuideDatabase
import au.edu.unimelb.floraguide.data.observation.OfflineFirstObservationRepository
import au.edu.unimelb.floraguide.data.plantnet.PlantNetClient
import au.edu.unimelb.floraguide.data.plantnet.PlantNetImageClassifier
import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.SpeciesContextRepository
import au.edu.unimelb.floraguide.domain.usecase.IdentifyStoredPhotoUseCase
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import au.edu.unimelb.floraguide.platform.LocationTracker
import au.edu.unimelb.floraguide.platform.SensorMonitor

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: FloraGuideDatabase by lazy {
        FloraGuideDatabase.getInstance(appContext)
    }

    val authRepository: AuthRepository = FirebaseAuthRepository()

    val isPlantNetConfigured: Boolean = BuildConfig.PLANTNET_API_KEY.isNotBlank()
    val imageClassifier: ImageClassifier = PlantNetImageClassifier(
        client = PlantNetClient(apiKey = BuildConfig.PLANTNET_API_KEY.trim()),
    )
    val photoStorage: PhotoStore = FirebasePhotoStorage(appContext)
    val identifyStoredPhoto = IdentifyStoredPhotoUseCase(photoStorage, imageClassifier)
    val speciesContextRepository: SpeciesContextRepository = AlaSpeciesContextRepository(AlaOccurrenceClient())

    val observationRepository: ObservationRepository = OfflineFirstObservationRepository(
        context = appContext,
        dao = database.observationDao()
    )

    val rankCandidates = RankSpeciesCandidatesUseCase()
    val sensorMonitor = SensorMonitor(appContext)
    val locationTracker = LocationTracker(appContext)
}
