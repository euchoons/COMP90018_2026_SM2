package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.core.content.edit
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Observation metadata remains local; image bytes are stored in Firebase Storage. */
class PreferencesObservationRepository(context: Context) : ObservationRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun loadAll(): List<Observation> = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(KEY_OBSERVATIONS, null) ?: return@withContext emptyList()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(ObservationJsonCodec::decode)
        }
    }

    override suspend fun save(observation: Observation) = withContext(Dispatchers.IO) {
        val current = loadAll().filterNot { it.id == observation.id }
        val array = JSONArray()
        (listOf(observation) + current).take(MAX_OBSERVATIONS).forEach {
            array.put(ObservationJsonCodec.encode(it))
        }
        preferences.edit { putString(KEY_OBSERVATIONS, array.toString()) }
    }

    private companion object {
        const val PREFERENCES_NAME = "floraguide_observations"
        const val KEY_OBSERVATIONS = "observations"
        const val MAX_OBSERVATIONS = 100
    }
}
