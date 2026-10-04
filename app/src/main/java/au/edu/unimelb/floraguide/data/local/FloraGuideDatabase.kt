package au.edu.unimelb.floraguide.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import net.sqlcipher.database.SQLiteDatabase
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom

@Database(entities = [ObservationEntity::class, ObservationImport::class], version = 2, exportSchema = false)
abstract class FloraGuideDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao

    companion object {
        @Volatile private var instance: FloraGuideDatabase? = null

        /**
         * Inspects the file header. Standard SQLite databases always begin with "SQLite format 3\0".
         * SQLCipher databases do not, providing a deterministic way to check encryption state.
         */
        private fun isPlaintext(dbFile: File): Boolean {
            if (!dbFile.exists() || dbFile.length() < 16) return false
            return dbFile.inputStream().use {
                val header = ByteArray(16)
                if (it.read(header) != 16) false else String(header) == "SQLite format 3\u0000"
            }
        }

        private fun retrieveOrGenerateSecureKey(context: Context): ByteArray {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            // Remove the plaintext Context.MODE_PRIVATE fallback.
            val prefs = EncryptedSharedPreferences.create(
                context,
                "secure_db_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            var passphrase = prefs.getString("sqlcipher_passphrase", null)
            if (passphrase == null) {
                val dbFile = context.getDatabasePath("floraguide.db")
                check(!(dbFile.exists() && !isPlaintext(dbFile))) {
                    "Encryption key is missing but the database is encrypted. Do not clear app data. Restore from backup if available, or contact support."
                }

                val randomBytes = ByteArray(32)
                SecureRandom().nextBytes(randomBytes)
                passphrase = Base64.encodeToString(randomBytes, Base64.NO_WRAP)

                check(prefs.edit().putString("sqlcipher_passphrase", passphrase).commit()) {
                    "Failed to persist the encryption key securely."
                }
            }

            // Databases encrypted since SQLCipher was added are keyed with these decoded bytes.
            return Base64.decode(passphrase, Base64.NO_WRAP)
        }

        private fun convertPlaintextToEncrypted(context: Context, passphrase: ByteArray) {
            val oldDb = context.getDatabasePath("floraguide.db")
            if (!oldDb.exists() || !isPlaintext(oldDb)) return

            val tmpDb = context.getDatabasePath("floraguide_enc.tmp")
            if (tmpDb.exists()) tmpDb.delete()

            // A blob key reaches SQLCipher as raw bytes, the same key SupportFactory(passphrase) uses.
            val keyBlob = passphrase.joinToString("") { "%02x".format(it) }
            var oldDbConn: SQLiteDatabase? = null

            try {
                // Open the existing plaintext DB with an empty password. ATTACH inherits these flags,
                // so without CREATE_IF_NECESSARY it cannot create the encrypted file.
                oldDbConn = SQLiteDatabase.openDatabase(
                    oldDb.absolutePath,
                    "",
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
                )
                // Attach a new encrypted temporary database and export the contents
                oldDbConn.rawExecSQL("ATTACH DATABASE '${tmpDb.absolutePath}' AS encrypted KEY X'$keyBlob';")
                oldDbConn.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                oldDbConn.rawExecSQL("DETACH DATABASE encrypted;")
            } finally {
                oldDbConn?.close()
            }

            // Validate the newly exported encrypted database
            var verifyDb: SQLiteDatabase? = null
            try {
                verifyDb = SQLiteDatabase.openDatabase(
                    tmpDb.absolutePath,
                    passphrase,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    null,
                    null
                )
                verifyDb.version // Trigger a read to guarantee structural integrity and successful encryption
            } finally {
                verifyDb?.close()
            }

            // Safely swap the original with the encrypted replacement
            check(oldDb.delete() && tmpDb.renameTo(oldDb)) {
                "Failed to replace old plaintext database with encrypted version."
            }

            context.getDatabasePath("floraguide.db-wal").delete()
            context.getDatabasePath("floraguide.db-shm").delete()
        }

        fun getInstance(context: Context): FloraGuideDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val hasSqlCipher = runCatching {
                    SQLiteDatabase.loadLibs(context.applicationContext)
                    true
                }.getOrDefault(false)

                val builder = if (hasSqlCipher) {
                    runCatching {
                        val passphrase = retrieveOrGenerateSecureKey(context.applicationContext)
                        convertPlaintextToEncrypted(context.applicationContext, passphrase)
                        Room.databaseBuilder(context.applicationContext, FloraGuideDatabase::class.java, "floraguide.db")
                            .openHelperFactory(SupportFactory(passphrase))
                    }.getOrNull()
                } else {
                    null
                }

                val dbBuilder = builder ?: Room.databaseBuilder(context.applicationContext, FloraGuideDatabase::class.java, "floraguide.db")

                dbBuilder
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
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
