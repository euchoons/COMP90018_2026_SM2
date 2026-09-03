package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.core.content.edit
import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Local prototype persistence. Swap this implementation for Firebase behind the same interface. */
class PreferencesObservationRepository(context: Context) : ObservationRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun loadAll(): List<Observation> {
        val encoded = preferences.getString(KEY_OBSERVATIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun save(observation: Observation) {
        val current = loadAll().toMutableList()
        current.removeAll { it.id == observation.id }
        current.add(0, observation)
        val array = JSONArray()
        current.take(MAX_OBSERVATIONS).forEach { array.put(encode(it)) }
        preferences.edit { putString(KEY_OBSERVATIONS, array.toString()) }
    }

    private fun encode(observation: Observation): JSONObject = JSONObject().apply {
        put("id", observation.id)
        put("speciesId", observation.species.id)
        put("observedAt", observation.observedAt.toString())
        put("latitude", observation.coarseLocation.latitude)
        put("longitude", observation.coarseLocation.longitude)
        put("habitat", observation.habitat.name)
        put("photoPath", observation.photoPath)
        put("headingDegrees", observation.headingDegrees)
        put("relativeScore", observation.relativeScore)
        put("contextSource", observation.contextSource.name)
    }

    private fun decode(json: JSONObject): Observation? {
        val species = DemoSpeciesCatalog.byId(json.optString("speciesId")) ?: return null
        return runCatching {
            Observation(
                id = json.getString("id"),
                species = species,
                observedAt = Instant.parse(json.getString("observedAt")),
                coarseLocation = GeoPoint(
                    latitude = json.getDouble("latitude"),
                    longitude = json.getDouble("longitude"),
                ),
                habitat = Habitat.valueOf(json.getString("habitat")),
                photoPath = json.optString("photoPath").takeIf { it.isNotBlank() && it != "null" },
                headingDegrees = if (json.has("headingDegrees") && !json.isNull("headingDegrees")) {
                    json.getDouble("headingDegrees").toFloat()
                } else {
                    null
                },
                relativeScore = json.getDouble("relativeScore"),
                contextSource = ContextDataSource.valueOf(json.getString("contextSource")),
            )
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES_NAME = "floraguide_observations"
        const val KEY_OBSERVATIONS = "observations"
        const val MAX_OBSERVATIONS = 100
    }
}
