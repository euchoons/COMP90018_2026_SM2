package au.edu.unimelb.floraguide.di

import android.content.Context
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.data.ala.AlaOccurrenceClient
import au.edu.unimelb.floraguide.data.ala.AlaSpeciesContextRepository
<<<<<<< HEAD
import au.edu.unimelb.floraguide.data.classifier.DemoImageClassifier
=======
import au.edu.unimelb.floraguide.data.classifier.PlantNetImageClassifier
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage
>>>>>>> parent of f129d0c (update new API code and settings)
import au.edu.unimelb.floraguide.data.observation.PreferencesObservationRepository
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.SpeciesContextRepository
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import au.edu.unimelb.floraguide.platform.LocationTracker
import au.edu.unimelb.floraguide.platform.SensorMonitor
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage


class AppContainer(context: Context) {
<<<<<<< HEAD
    // Without a configured key the app still runs; the results screen then says so.
    val imageClassifier: ImageClassifier =
        if (BuildConfig.PLANTNET_API_KEY.isBlank()) {
            DemoImageClassifier()
        } else {
            PlantNetImageClassifier(PlantNetClient(apiKey = BuildConfig.PLANTNET_API_KEY))
        }
    val speciesContextRepository: SpeciesContextRepository =
        AlaSpeciesContextRepository(AlaOccurrenceClient())
    val observationRepository: ObservationRepository =
        PreferencesObservationRepository(context)
    val photoStorage = FirebasePhotoStorage(context.applicationContext)
    val rankCandidates = RankSpeciesCandidatesUseCase()
    val sensorMonitor = SensorMonitor(context)
    val locationTracker = LocationTracker(context)
}
=======

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
>>>>>>> parent of f129d0c (update new API code and settings)
