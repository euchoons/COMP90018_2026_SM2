package au.edu.unimelb.floraguide.data.observation

import android.content.Context
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
        // Strip out the Context.MODE_PRIVATE fallback to guarantee secure storage invariants.
        throw IllegalStateException("Secure keystore unavailable. Cannot safely load or store legacy preferences.", it)
    }

    override suspend fun loadAll(): List<Observation> = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(KEY_OBSERVATIONS, null) ?: return@withContext emptyList()
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
        check(preferences.edit().putString(KEY_OBSERVATIONS, array.toString()).commit()) {
            "Failed to commit legacy preferences securely."
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "floraguide_observations"
        const val KEY_OBSERVATIONS = "observations"
        const val MAX_OBSERVATIONS = 100
    }
}
