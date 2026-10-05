package au.edu.unimelb.floraguide.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import net.zetetic.database.sqlcipher.SQLiteDatabase
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

        private fun getSecureSharedPreferences(context: Context): android.content.SharedPreferences {
            return runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    "secure_db_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse {
                runCatching {
                    context.deleteSharedPreferences("secure_db_prefs")
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    EncryptedSharedPreferences.create(
                        context,
                        "secure_db_prefs",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }.getOrElse {
                    context.getSharedPreferences("secure_db_prefs", Context.MODE_PRIVATE)
                }
            }
        }

        private fun purgeDatabaseFiles(context: Context) {
            runCatching {
                val dbFile = context.getDatabasePath("floraguide.db")
                val backupFile = context.getDatabasePath("floraguide.db.corrupt")
                if (backupFile.exists()) backupFile.delete()
                if (dbFile.exists()) dbFile.renameTo(backupFile)
                context.getDatabasePath("floraguide.db-wal").delete()
                context.getDatabasePath("floraguide.db-shm").delete()
                context.getDatabasePath("floraguide_enc.tmp").delete()
            }
        }

        private fun retrieveOrGenerateSecureKey(context: Context, forceRegenerate: Boolean = false): ByteArray {
            val prefs = getSecureSharedPreferences(context)

            var passphrase = if (forceRegenerate) null else prefs.getString("sqlcipher_passphrase", null)
            if (passphrase == null) {
                if (forceRegenerate) {
                    purgeDatabaseFiles(context)
                }

                val randomBytes = ByteArray(32)
                SecureRandom().nextBytes(randomBytes)
                passphrase = Base64.encodeToString(randomBytes, Base64.NO_WRAP)

                check(prefs.edit().putString("sqlcipher_passphrase", passphrase).commit()) {
                    "Failed to persist the encryption key securely."
                }
            }

            // Return UTF-8 bytes of the Base64 string to ensure exact matching between SupportOpenHelperFactory and ATTACH string literals.
            return passphrase.toByteArray(Charsets.UTF_8)
        }

        private fun convertPlaintextToEncrypted(context: Context, passphraseBytes: ByteArray) {
            val oldDb = context.getDatabasePath("floraguide.db")
            if (!oldDb.exists() || !isPlaintext(oldDb)) return

            val tmpDb = context.getDatabasePath("floraguide_enc.tmp")
            if (tmpDb.exists()) tmpDb.delete()
            tmpDb.parentFile?.mkdirs()

            // Pre-create the encrypted temporary database file so ATTACH DATABASE can open it successfully
            SQLiteDatabase.openOrCreateDatabase(tmpDb.absolutePath, passphraseBytes, null, null).close()

            val passphraseStr = String(passphraseBytes, Charsets.UTF_8)
            var oldDbConn: SQLiteDatabase? = null

            try {
                // Open the existing plaintext DB
                oldDbConn = SQLiteDatabase.openDatabase(
                    oldDb.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE
                )
                // Disable plaintext header check so SQLCipher 4 can open unencrypted databases
                oldDbConn.rawExecSQL("PRAGMA cipher_plaintext_header_check = OFF;")
                // Checkpoint WAL to ensure all written rows are flushed to the main database file
                oldDbConn.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
                // Attach the pre-created encrypted temporary database and export the contents
                oldDbConn.rawExecSQL("ATTACH DATABASE '${tmpDb.absolutePath}' AS encrypted KEY '$passphraseStr';")
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
                    passphraseBytes,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
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

        private fun buildAndVerifyDatabase(context: Context, passphrase: ByteArray?): FloraGuideDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, FloraGuideDatabase::class.java, "floraguide.db")
                .addMigrations(MIGRATION_1_2)
            if (passphrase != null) {
                builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
            }
            val db = builder.build()
            // Force immediate connection opening and verification to catch decryption/corruption errors inside getInstance
            db.openHelper.writableDatabase.version
            return db
        }

        fun getInstance(context: Context): FloraGuideDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val hasSqlCipher = runCatching {
                    System.loadLibrary("sqlcipher")
                    true
                }.getOrDefault(false)

                val db = try {
                    if (hasSqlCipher) {
                        val passphrase = retrieveOrGenerateSecureKey(appContext)
                        convertPlaintextToEncrypted(appContext, passphrase)
                        buildAndVerifyDatabase(appContext, passphrase)
                    } else {
                        buildAndVerifyDatabase(appContext, null)
                    }
                } catch (throwable: Throwable) {
                    runCatching { android.util.Log.e("FloraGuideDatabase", "SQLCipher database open/decryption failed, recreating database: ${throwable.localizedMessage}") }
                    purgeDatabaseFiles(appContext)

                    if (hasSqlCipher) {
                        val passphrase = retrieveOrGenerateSecureKey(appContext, forceRegenerate = true)
                        buildAndVerifyDatabase(appContext, passphrase)
                    } else {
                        buildAndVerifyDatabase(appContext, null)
                    }
                }

                db.also { instance = it }
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
