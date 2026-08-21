package au.edu.unimelb.bioscout.di

import android.content.Context
import au.edu.unimelb.bioscout.data.ala.AlaOccurrenceClient
import au.edu.unimelb.bioscout.data.ala.AlaSpeciesContextRepository
import au.edu.unimelb.bioscout.data.classifier.DemoImageClassifier
import au.edu.unimelb.bioscout.data.observation.PreferencesObservationRepository
import au.edu.unimelb.bioscout.domain.repository.ImageClassifier
import au.edu.unimelb.bioscout.domain.repository.ObservationRepository
import au.edu.unimelb.bioscout.domain.repository.SpeciesContextRepository
import au.edu.unimelb.bioscout.domain.usecase.RankSpeciesCandidatesUseCase
import au.edu.unimelb.bioscout.platform.LocationTracker
import au.edu.unimelb.bioscout.platform.SensorMonitor

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
