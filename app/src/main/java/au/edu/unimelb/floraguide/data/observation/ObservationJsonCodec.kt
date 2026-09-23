package au.edu.unimelb.floraguide.data.observation

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.CandidateEvidenceRecord
import au.edu.unimelb.floraguide.domain.model.CaptureLocationSource
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Schema 3 stores ALA/capture provenance while remaining compatible with existing Room columns. */
internal object ObservationJsonCodec {
    fun encode(observation: Observation): JSONObject = JSONObject().apply {
        put("schemaVersion", 3)
        put("id", observation.id)
        put("speciesId", observation.species.id)
        put("species", encodeSpecies(observation.species))
        put("observedAt", observation.observedAt.toString())
        put("confirmedAt", observation.confirmedAt?.toString() ?: JSONObject.NULL)
        put("latitude", observation.coarseLocation.latitude)
        put("longitude", observation.coarseLocation.longitude)
        put("locationSource", observation.locationSource.name)
        put("habitat", observation.habitat.name)
        put("photoPath", observation.photoPath ?: JSONObject.NULL)
        put("cloudPhotoUri", observation.cloudPhotoUri ?: JSONObject.NULL)
        put("imageScore", observation.imageScore ?: JSONObject.NULL)
        put("imageSource", observation.imageSource?.name ?: JSONObject.NULL)
        put("headingDegrees", observation.headingDegrees ?: JSONObject.NULL)
        put("relativeScore", observation.relativeScore)
        put("contextSource", observation.contextSource.name)
        put("nearbyRecordCount", observation.nearbyRecordCount ?: JSONObject.NULL)
        put("contextRadiusKm", observation.contextRadiusKm ?: JSONObject.NULL)
        put("contextQueriedAt", observation.contextQueriedAt?.toString() ?: JSONObject.NULL)
        put("rankingRule", observation.rankingRule ?: JSONObject.NULL)
        put("verificationStatus", observation.verificationStatus)
        put("candidateEvidence", JSONArray().apply {
            observation.candidateEvidence.forEach { item ->
                put(JSONObject().apply {
                    put("scientificName", item.scientificName)
                    put("imageScore", item.imageScore)
                    put("finalRelativeScore", item.finalRelativeScore)
                    put("nearbyRecordCount", item.nearbyRecordCount ?: JSONObject.NULL)
                    put("lookupFailure", item.lookupFailure ?: JSONObject.NULL)
                })
            }
        })
    }

    fun decode(json: JSONObject): Observation? = runCatching {
        val species = json.optJSONObject("species")?.let(::decodeSpecies)
            ?: DemoSpeciesCatalog.byId(json.optString("speciesId"))
            ?: return null
        val latitude = json.optionalDouble("latitude") ?: return null
        val longitude = json.optionalDouble("longitude") ?: return null
        val location = GeoPoint(latitude, longitude).also { require(it.hasValidCoordinates()) }
        val evidence = json.optJSONArray("candidateEvidence")
        val schemaVersion = json.optInt("schemaVersion", 1)
        val storedSource = enumValueOrNull<ContextDataSource>(json.optionalString("contextSource"))
            ?: ContextDataSource.NOT_REQUESTED
        val contextSource = if (
            schemaVersion < 3 &&
            (storedSource == ContextDataSource.ALA_PARTIAL || storedSource == ContextDataSource.DEMO_FALLBACK)
        ) {
            ContextDataSource.LEGACY_UNVERIFIED
        } else {
            storedSource
        }

        Observation(
            id = json.getString("id"),
            species = species,
            observedAt = Instant.parse(json.getString("observedAt")),
            coarseLocation = location,
            habitat = Habitat.valueOf(json.getString("habitat")),
            photoPath = json.optionalString("photoPath"),
            cloudPhotoUri = json.optionalString("cloudPhotoUri"),
            imageScore = json.optionalDouble("imageScore"),
            imageSource = enumValueOrNull<ImageSource>(json.optionalString("imageSource")),
            headingDegrees = json.optionalDouble("headingDegrees")?.toFloat(),
            relativeScore = json.getDouble("relativeScore"),
            contextSource = contextSource,
            locationSource = enumValueOrNull<CaptureLocationSource>(json.optionalString("locationSource"))
                ?: CaptureLocationSource.LEGACY_UNKNOWN,
            confirmedAt = json.optionalString("confirmedAt")?.let(Instant::parse),
            nearbyRecordCount = json.optionalCount("nearbyRecordCount"),
            contextRadiusKm = json.optionalCount("contextRadiusKm"),
            contextQueriedAt = json.optionalString("contextQueriedAt")?.let(Instant::parse),
            rankingRule = json.optionalString("rankingRule"),
            candidateEvidence = (0 until (evidence?.length() ?: 0)).mapNotNull { index ->
                val item = evidence?.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    CandidateEvidenceRecord(
                        scientificName = item.getString("scientificName"),
                        imageScore = item.getDouble("imageScore"),
                        finalRelativeScore = item.getDouble("finalRelativeScore"),
                        nearbyRecordCount = item.optionalCount("nearbyRecordCount"),
                        lookupFailure = item.optionalString("lookupFailure"),
                    )
                }.getOrNull()
            },
            verificationStatus = json.optionalString("verificationStatus") ?: "LEGACY_UNVERIFIED",
        )
    }.getOrNull()

    private fun encodeSpecies(species: Species): JSONObject = JSONObject().apply {
        put("id", species.id)
        put("commonName", species.commonName)
        put("scientificName", species.scientificName)
        put("preferredMonths", JSONArray(species.preferredMonths.sorted()))
        put("habitatAffinity", JSONObject().apply {
            species.habitatAffinity.forEach { (habitat, value) -> put(habitat.name, value) }
        })
        put("demoNearbyCount", species.demoNearbyCount)
    }

    private fun decodeSpecies(json: JSONObject): Species {
        val name = json.getString("scientificName").trim()
        require(name.isNotEmpty())
        val months = json.optJSONArray("preferredMonths")
        val affinity = json.optJSONObject("habitatAffinity")
        return Species(
            id = json.getString("id"),
            commonName = json.optString("commonName").ifBlank { name },
            scientificName = name,
            preferredMonths = (0 until (months?.length() ?: 0)).mapNotNull {
                months?.optInt(it)?.takeIf { month -> month in 1..12 }
            }.toSet(),
            habitatAffinity = Habitat.entries.mapNotNull { habitat ->
                affinity?.optionalDouble(habitat.name)?.let { habitat to it }
            }.toMap(),
            demoNearbyCount = json.optInt("demoNearbyCount", 0).coerceAtLeast(0),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optionalDouble(name: String): Double? =
        (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun JSONObject.optionalCount(name: String): Int? = optionalDouble(name)?.let {
        if (it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble()) it.toInt() else null
    }
}
