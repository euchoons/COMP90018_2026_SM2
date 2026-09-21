package au.edu.unimelb.floraguide.data.observation

import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.*
import java.time.Instant
import org.json.JSONObject

internal fun Observation.toEntity(userId: String) = ObservationEntity(
    id = id, userId = userId, speciesId = species.id, scientificName = species.scientificName,
    commonName = species.commonName, preferredMonthsCsv = species.preferredMonths.joinToString(","),
    habitatAffinityJson = JSONObject(species.habitatAffinity.mapKeys { it.key.name }).toString(),
    observedAtEpochMs = observedAt.toEpochMilli(), coarseLatitude = coarseLocation.latitude,
    coarseLongitude = coarseLocation.longitude, habitatName = habitat.name, localPhotoPath = photoPath,
    remotePhotoUrl = cloudPhotoUri, headingDegrees = headingDegrees, relativeScore = relativeScore,
    contextSource = contextSource.name, syncState = SyncState.PENDING_UPLOAD,
    observationJson = ObservationJsonCodec.encode(this).toString(),
)

internal fun ObservationEntity.toObservation(): Observation {
    val decoded = observationJson?.let { runCatching { ObservationJsonCodec.decode(JSONObject(it)) }.getOrNull() }
    val affinity = runCatching { JSONObject(habitatAffinityJson) }.getOrDefault(JSONObject())
    val legacy = decoded ?: Observation(
        id = id,
        species = Species(speciesId, commonName, scientificName,
            preferredMonthsCsv.split(",").mapNotNull(String::toIntOrNull).toSet(),
            Habitat.entries.mapNotNull { h ->
                (affinity.opt(h.name) as? Number)?.toDouble()?.takeIf(Double::isFinite)?.let { h to it }
            }.toMap(), 0),
        observedAt = Instant.ofEpochMilli(observedAtEpochMs),
        coarseLocation = GeoPoint(coarseLatitude, coarseLongitude),
        habitat = Habitat.valueOf(habitatName), photoPath = localPhotoPath,
        headingDegrees = headingDegrees, relativeScore = relativeScore,
        contextSource = ContextDataSource.valueOf(contextSource),
    )
    return legacy.copy(photoPath = localPhotoPath ?: remotePhotoUrl, cloudPhotoUri = remotePhotoUrl)
}
