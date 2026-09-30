package au.edu.unimelb.floraguide.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom



@Database(entities = [ObservationEntity::class, ObservationImport::class], version = 2, exportSchema = false)
abstract class FloraGuideDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao

    companion object {
        @Volatile private var instance: FloraGuideDatabase? = null
        private fun retrieveOrGenerateSecureKey(context: Context): ByteArray {
            val keyString = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    "secure_db_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                var passphrase = encryptedPrefs.getString("sqlcipher_passphrase", null)
                if (passphrase == null) {
                    val randomBytes = ByteArray(32)
                    SecureRandom().nextBytes(randomBytes)
                    passphrase = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
                    encryptedPrefs.edit { putString("sqlcipher_passphrase", passphrase) }
                }
                passphrase
            }.getOrElse {
                val fallbackPrefs = context.getSharedPreferences("secure_db_prefs_fallback", Context.MODE_PRIVATE)
                var passphrase = runCatching { fallbackPrefs.getString("sqlcipher_passphrase", null) }.getOrNull()
                if (passphrase == null) {
                    val randomBytes = ByteArray(32)
                    SecureRandom().nextBytes(randomBytes)
                    passphrase = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
                    fallbackPrefs.edit { putString("sqlcipher_passphrase", passphrase) }
                }
                passphrase
            }

            return Base64.decode(keyString, Base64.NO_WRAP)
        }

        fun getInstance(context: Context): FloraGuideDatabase = instance ?: synchronized(this) {
            instance ?: run { createDatabase(context).also { instance = it } }
        }

        private fun createDatabase(context: Context): FloraGuideDatabase {
            val dbName = "floraguide.db"
            val appContext = context.applicationContext

            val hasSqlCipher = runCatching {
                net.sqlcipher.database.SQLiteDatabase.loadLibs(appContext)
                true
            }.getOrDefault(false)

            fun buildHelper(): FloraGuideDatabase {
                val builder = Room.databaseBuilder(appContext, FloraGuideDatabase::class.java, dbName)
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(true)

                if (hasSqlCipher) {
                    runCatching {
                        val passphrase: ByteArray = retrieveOrGenerateSecureKey(appContext)
                        val factory = SupportFactory(passphrase)
                        builder.openHelperFactory(factory)
                    }
                }
                return builder.build()
            }

            return try {
                val db = buildHelper()
                db.openHelper.writableDatabase
                db
            } catch (_: Exception) {
                runCatching { appContext.deleteDatabase(dbName) }
                val cleanDb = buildHelper()
                runCatching { cleanDb.openHelper.writableDatabase }
                cleanDb
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val oldColumns = db.query("PRAGMA table_info(cached_observations)").use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                }
                db.execSQL("""
                    CREATE TABLE cached_observations_v2 (
                        id TEXT NOT NULL, userId TEXT NOT NULL, speciesId TEXT NOT NULL,
                        scientificName TEXT NOT NULL, commonName TEXT NOT NULL,
                        preferredMonthsCsv TEXT NOT NULL DEFAULT '', habitatAffinityJson TEXT NOT NULL DEFAULT '{}',
                        observedAtEpochMs INTEGER NOT NULL, coarseLatitude REAL NOT NULL, coarseLongitude REAL NOT NULL,
                        habitatName TEXT NOT NULL, localPhotoPath TEXT, remotePhotoUrl TEXT, headingDegrees REAL,
                        relativeScore REAL NOT NULL, contextSource TEXT NOT NULL, syncState TEXT NOT NULL,
                        retryCount INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, observationJson TEXT,
                        PRIMARY KEY(userId, id))
                """.trimIndent())
                // PR23 added two columns without incrementing version 1; accept either old shape.
                val months = if ("preferredMonthsCsv" in oldColumns) "preferredMonthsCsv" else "''"
                val habitat = if ("habitatAffinityJson" in oldColumns) "habitatAffinityJson" else "'{}'"
                db.execSQL("""
                    INSERT INTO cached_observations_v2 SELECT
                        id, userId, speciesId, scientificName, commonName, $months, $habitat,
                        observedAtEpochMs, coarseLatitude, coarseLongitude, habitatName, localPhotoPath,
                        remotePhotoUrl, headingDegrees, relativeScore, contextSource, syncState, retryCount, 0, NULL
                    FROM cached_observations
                """.trimIndent())
                db.execSQL("DROP TABLE cached_observations")
                db.execSQL("ALTER TABLE cached_observations_v2 RENAME TO cached_observations")
                db.execSQL("CREATE TABLE observation_imports (name TEXT NOT NULL PRIMARY KEY)")
            }
        }
    }
}
