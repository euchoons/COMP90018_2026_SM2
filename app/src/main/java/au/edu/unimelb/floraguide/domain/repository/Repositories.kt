package au.edu.unimelb.floraguide.domain.repository

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species

interface ImageClassifier {
    /**
     * Produces a candidate set for reranking, tagged with the source that produced it. Cloud
     * (Pl@ntNet), on-device (future TensorFlow Lite) and demo adapters all implement this seam.
     */
    suspend fun classify(photoPath: String?): ImageClassification
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
