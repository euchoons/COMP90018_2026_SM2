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
    suspend fun loadAll(): List<Observation>
    suspend fun save(observation: Observation)
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Observation>> =
        kotlinx.coroutines.flow.flow { emit(loadAll()) }
    suspend fun delete(id: String) { error("Deletion is not supported by this observation store.") }
    suspend fun importLocalObservations() { error("Import is not supported by this observation store.") }
    suspend fun retrySync() { error("Sync is not supported by this observation store.") }
}
