package au.edu.unimelb.floraguide.data.observation

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Stores the full species, while remaining compatible with older demo-only records. */
internal object ObservationJsonCodec {
    fun encode(observation: Observation): JSONObject = JSONObject().apply {
        put("schemaVersion", 2)
        put("id", observation.id)
        put("speciesId", observation.species.id)
        put("species", encodeSpecies(observation.species))
        put("observedAt", observation.observedAt.toString())
        put("latitude", observation.coarseLocation.latitude)
        put("longitude", observation.coarseLocation.longitude)
        put("habitat", observation.habitat.name)
        put("photoPath", observation.photoPath ?: JSONObject.NULL)
        put("cloudPhotoUri", observation.cloudPhotoUri ?: JSONObject.NULL)
        put("imageScore", observation.imageScore ?: JSONObject.NULL)
        put("imageSource", observation.imageSource?.name ?: JSONObject.NULL)
        put("headingDegrees", observation.headingDegrees ?: JSONObject.NULL)
        put("relativeScore", observation.relativeScore)
        put("contextSource", observation.contextSource.name)
    }

    fun decode(json: JSONObject): Observation? = runCatching {
        val species = json.optJSONObject("species")?.let(::decodeSpecies)
            ?: DemoSpeciesCatalog.byId(json.optString("speciesId"))
            ?: return null
        Observation(
            id = json.getString("id"),
            species = species,
            observedAt = Instant.parse(json.getString("observedAt")),
            coarseLocation = GeoPoint(json.getDouble("latitude"), json.getDouble("longitude")),
            habitat = Habitat.valueOf(json.getString("habitat")),
            photoPath = json.optionalString("photoPath"),
            cloudPhotoUri = json.optionalString("cloudPhotoUri"),
            imageScore = json.optionalDouble("imageScore"),
            imageSource = json.optionalString("imageSource")?.let { ImageSource.valueOf(it) },
            headingDegrees = json.optionalDouble("headingDegrees")?.toFloat(),
            relativeScore = json.getDouble("relativeScore"),
            contextSource = ContextDataSource.valueOf(json.getString("contextSource")),
        )
    }.getOrNull()

    private fun encodeSpecies(species: Species): JSONObject = JSONObject().apply {
        put("id", species.id)
        put("commonName", species.commonName)
        put("scientificName", species.scientificName)
        put("preferredMonths", JSONArray(species.preferredMonths.sorted()))
        put("habitatAffinity", JSONObject().apply {
            species.habitatAffinity.forEach { (habitat, score) -> put(habitat.name, score) }
        })
        put("demoNearbyCount", species.demoNearbyCount)
    }

    private fun decodeSpecies(json: JSONObject): Species {
        val scientificName = json.getString("scientificName").trim()
        require(scientificName.isNotEmpty())
        val months = json.optJSONArray("preferredMonths")
        val affinity = json.optJSONObject("habitatAffinity")
        return Species(
            id = json.getString("id"),
            commonName = json.optString("commonName").ifBlank { scientificName },
            scientificName = scientificName,
            preferredMonths = (0 until (months?.length() ?: 0)).mapNotNull {
                months?.optInt(it)?.takeIf { month -> month in 1..12 }
            }.toSet(),
            habitatAffinity = Habitat.entries.mapNotNull { habitat ->
                affinity?.optionalDouble(habitat.name)?.let { habitat to it }
            }.toMap(),
            demoNearbyCount = json.optInt("demoNearbyCount", 0).coerceAtLeast(0),
        )
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optionalDouble(name: String): Double? =
        (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
}
