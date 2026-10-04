package au.edu.unimelb.floraguide.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        SQLiteDatabase.loadLibs(context)
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

    @Test fun databaseEncryptedByMainStillOpens() = runBlocking {
        // main stores Base64 of 32 random bytes and keys SupportFactory with the decoded bytes.
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertTrue(securePrefs().edit().putString("sqlcipher_passphrase", Base64.encodeToString(raw, Base64.NO_WRAP)).commit())
        Room.databaseBuilder(context, FloraGuideDatabase::class.java, "floraguide.db")
            .openHelperFactory(SupportFactory(raw.copyOf())).build().apply {
                observationDao().insertOrUpdate(row("encrypted"))
                close()
            }
        val db = FloraGuideDatabase.getInstance(context)
        assertNotNull(db.observationDao().find("u1", "encrypted"))
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
