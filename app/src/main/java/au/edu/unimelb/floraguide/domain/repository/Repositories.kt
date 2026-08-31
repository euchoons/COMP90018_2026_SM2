package au.edu.unimelb.floraguide.domain.repository

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species

interface ImageClassifier {
    /**
     * Produces a candidate set for reranking. The current baseline implementation is deliberately a
     * deterministic demo adapter; replace it with TensorFlow Lite without changing the UI.
     */
    suspend fun classify(photoPath: String?): List<ImagePrediction>
}

interface SpeciesContextRepository {
    suspend fun nearbyOccurrenceCounts(
        candidates: List<Species>,
        location: GeoPoint,
        radiusKm: Int,
        preferLiveData: Boolean,
    ): NearbyContext
}

interface ObservationRepository {
    fun loadAll(): List<Observation>
    fun save(observation: Observation)
}
