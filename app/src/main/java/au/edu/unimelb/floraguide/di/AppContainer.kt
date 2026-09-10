package au.edu.unimelb.floraguide.di

import android.content.Context
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.data.ala.AlaOccurrenceClient
import au.edu.unimelb.floraguide.data.ala.AlaSpeciesContextRepository
import au.edu.unimelb.floraguide.data.classifier.PlantNetImageClassifier
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage
import au.edu.unimelb.floraguide.data.observation.PreferencesObservationRepository
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.SpeciesContextRepository
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import au.edu.unimelb.floraguide.platform.LocationTracker
import au.edu.unimelb.floraguide.platform.SensorMonitor

class AppContainer(context: Context) {

    val imageClassifier: ImageClassifier =
        PlantNetImageClassifier(
            apiKey = BuildConfig.PLANTNET_API_KEY
        )

    val speciesContextRepository: SpeciesContextRepository =
        AlaSpeciesContextRepository(
            AlaOccurrenceClient()
        )

    val observationRepository: ObservationRepository =
        PreferencesObservationRepository(context)

    val photoStorage =
        FirebasePhotoStorage()

    val rankCandidates =
        RankSpeciesCandidatesUseCase()

    val sensorMonitor =
        SensorMonitor(context)

    val locationTracker =
        LocationTracker(context)
}