package au.edu.unimelb.floraguide.di

import android.content.Context
import au.edu.unimelb.floraguide.data.ala.AlaOccurrenceClient
import au.edu.unimelb.floraguide.data.ala.AlaSpeciesContextRepository
import au.edu.unimelb.floraguide.data.classifier.DemoImageClassifier
import au.edu.unimelb.floraguide.data.observation.PreferencesObservationRepository
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import au.edu.unimelb.floraguide.domain.repository.SpeciesContextRepository
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import au.edu.unimelb.floraguide.platform.LocationTracker
import au.edu.unimelb.floraguide.platform.SensorMonitor

class AppContainer(context: Context) {
    val imageClassifier: ImageClassifier = DemoImageClassifier()
    val speciesContextRepository: SpeciesContextRepository =
        AlaSpeciesContextRepository(AlaOccurrenceClient())
    val observationRepository: ObservationRepository =
        PreferencesObservationRepository(context)
    val rankCandidates = RankSpeciesCandidatesUseCase()
    val sensorMonitor = SensorMonitor(context)
    val locationTracker = LocationTracker(context)
}
