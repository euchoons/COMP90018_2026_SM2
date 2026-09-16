package au.edu.unimelb.floraguide.di

import android.content.Context
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.data.ala.AlaOccurrenceClient
import au.edu.unimelb.floraguide.data.ala.AlaSpeciesContextRepository
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage
import au.edu.unimelb.floraguide.data.observation.PreferencesObservationRepository
import au.edu.unimelb.floraguide.data.plantnet.PlantNetClient
import au.edu.unimelb.floraguide.data.plantnet.PlantNetImageClassifier
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
    val isPlantNetConfigured: Boolean = BuildConfig.PLANTNET_API_KEY.isNotBlank()
    // This is the adapter covered by the existing data.plantnet tests.
    val imageClassifier: ImageClassifier = PlantNetImageClassifier(
        client = PlantNetClient(apiKey = BuildConfig.PLANTNET_API_KEY.trim()),
    )
    val photoStorage: PhotoStore = FirebasePhotoStorage(appContext)
    val identifyStoredPhoto = IdentifyStoredPhotoUseCase(photoStorage, imageClassifier)
    val speciesContextRepository: SpeciesContextRepository = AlaSpeciesContextRepository(AlaOccurrenceClient())
    val observationRepository: ObservationRepository = PreferencesObservationRepository(appContext)
    val rankCandidates = RankSpeciesCandidatesUseCase()
    val sensorMonitor = SensorMonitor(appContext)
    val locationTracker = LocationTracker(appContext)
}
