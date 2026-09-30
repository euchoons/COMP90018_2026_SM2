package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.core.content.edit
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Legacy local observation store used only for one-time import into the offline-first repository. */
class PreferencesObservationRepository(private val context: Context) : ObservationRepository {
    private val preferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun loadAll(): List<Observation> = withContext(Dispatchers.IO) {
        val encoded = runCatching { preferences.getString(KEY_OBSERVATIONS, null) }.getOrNull()
            ?: runCatching { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY_OBSERVATIONS, null) }.getOrNull()
            ?: return@withContext emptyList()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(ObservationJsonCodec::decode)
        }
    }

    override suspend fun save(observation: Observation): Unit = withContext(Dispatchers.IO) {
        val current = loadAll().filterNot { it.id == observation.id }
        val array = JSONArray()
        (listOf(observation) + current).take(MAX_OBSERVATIONS).forEach {
            array.put(ObservationJsonCodec.encode(it))
        }
        val content = array.toString()
        try {
            preferences.edit { putString(KEY_OBSERVATIONS, content) }
        } catch (_: Throwable) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit { putString(KEY_OBSERVATIONS, content) }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "floraguide_observations"
        const val KEY_OBSERVATIONS = "observations"
        const val MAX_OBSERVATIONS = 100
    }
}
