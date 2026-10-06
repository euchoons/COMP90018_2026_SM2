package au.edu.unimelb.floraguide.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class SqlCipherUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        FloraGuideDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.deleteDatabase("floraguide.db")
        context.getDatabasePath("floraguide_enc.tmp").delete()
        context.deleteSharedPreferences("secure_db_prefs")
        System.loadLibrary("sqlcipher")
    }

    @Test fun plaintextDatabaseIsEncryptedWithItsRows() = runBlocking {
        Room.databaseBuilder(context, FloraGuideDatabase::class.java, "floraguide.db").build().apply {
            observationDao().insertOrUpdate(row("plain"))
            close()
        }
        assertTrue(header().startsWith("SQLite format 3"))
        val db = FloraGuideDatabase.getInstance(context)
        assertNotNull(db.observationDao().find("u1", "plain"))
        assertEquals(2, db.openHelper.readableDatabase.version)
        db.close()
        assertFalse(header().startsWith("SQLite format 3"))
    }

    @Test fun databaseEncryptedWithLegacyBase64BytesStillOpens() = runBlocking {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encodeToString(raw, Base64.NO_WRAP)
        assertTrue(securePrefs().edit().putString("sqlcipher_passphrase", passphrase).commit())

        // Simulates the original standard where OpenHelper Factory relied directly on Base64 decoded bytes
        Room.databaseBuilder(context, FloraGuideDatabase::class.java, "floraguide.db")
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(raw)).build().apply {
                observationDao().insertOrUpdate(row("encrypted_legacy"))
                close()
            }

        val db = FloraGuideDatabase.getInstance(context)
        assertNotNull(db.observationDao().find("u1", "encrypted_legacy"))
        db.close()
    }

    @Test fun databaseEncryptedWithUtf8StringBytesStillOpens() = runBlocking {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encodeToString(raw, Base64.NO_WRAP)
        assertTrue(securePrefs().edit().putString("sqlcipher_passphrase", passphrase).commit())

        // Simulates the interim structural bug where OpenHelper Factory used UTF-8 bytes from the base64 string
        Room.databaseBuilder(context, FloraGuideDatabase::class.java, "floraguide.db")
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))).build().apply {
                observationDao().insertOrUpdate(row("encrypted_utf8"))
                close()
            }

        val db = FloraGuideDatabase.getInstance(context)
        assertNotNull(db.observationDao().find("u1", "encrypted_utf8"))
        db.close()
    }

    @Test fun newInstallCreatesAnEncryptedDatabase() = runBlocking {
        val db = FloraGuideDatabase.getInstance(context)
        db.observationDao().insertOrUpdate(row("fresh"))
        db.close()
        assertFalse(header().startsWith("SQLite format 3"))
        FloraGuideDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val reopened = FloraGuideDatabase.getInstance(context)
        assertNotNull(reopened.observationDao().find("u1", "fresh"))
        reopened.close()
    }

    @Test fun missingKeyForAnEncryptedDatabaseIsRefused() = runBlocking {
        FloraGuideDatabase.getInstance(context).apply {
            observationDao().insertOrUpdate(row("kept"))
            close()
        }
        val before = context.getDatabasePath("floraguide.db").readBytes()
        context.deleteSharedPreferences("secure_db_prefs")
        FloraGuideDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)

        assertTrue(runCatching { FloraGuideDatabase.getInstance(context) }.exceptionOrNull() is IllegalStateException)
        assertTrue(before.contentEquals(context.getDatabasePath("floraguide.db").readBytes()))
        assertNull(securePrefs().getString("sqlcipher_passphrase", null))
    }

    private fun header() = String(context.getDatabasePath("floraguide.db").readBytes().copyOf(15))

    private fun securePrefs() = EncryptedSharedPreferences.create(
        context, "secure_db_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun row(id: String) = ObservationEntity(
        id = id, userId = "u1", speciesId = "s", scientificName = "S s", commonName = "S",
        observedAtEpochMs = 1, coarseLatitude = 0.0, coarseLongitude = 0.0, habitatName = "LAWN",
        localPhotoPath = null, remotePhotoUrl = null, headingDegrees = null, relativeScore = 0.5,
        contextSource = "DEMO_FALLBACK", syncState = SyncState.SYNCED,
    )
}
