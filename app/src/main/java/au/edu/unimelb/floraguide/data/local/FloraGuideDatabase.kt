package au.edu.unimelb.floraguide.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

@Database(entities = [ObservationEntity::class, ObservationImport::class], version = 2, exportSchema = false)
abstract class FloraGuideDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao

    companion object {
        @Volatile private var instance: FloraGuideDatabase? = null

        fun getInstance(context: Context): FloraGuideDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val hasSqlCipher = runCatching {
                    System.loadLibrary("sqlcipher")
                    true
                }.getOrDefault(false)

                val builder = Room.databaseBuilder(appContext, FloraGuideDatabase::class.java, "floraguide.db")
                    .addMigrations(MIGRATION_1_2)

                if (hasSqlCipher) {
                    val passphrase = DatabaseSecurityManager.getWorkingPassphrase(appContext)
                    DatabaseSecurityManager.convertPlaintextToEncrypted(appContext, passphrase)
                    builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
                }

                builder.build().also { instance = it }
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

/**
 * Isolates cryptographic key management, migration workflows, and format fallback heuristics.
 */
internal object DatabaseSecurityManager {

    fun getWorkingPassphrase(context: Context): ByteArray {
        val prefs = getSecureSharedPreferences(context)
        var passphraseB64 = prefs.getString("sqlcipher_passphrase", null)

        if (passphraseB64 == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            passphraseB64 = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
            check(prefs.edit().putString("sqlcipher_passphrase", passphraseB64).commit()) {
                "Failed to persist the encryption key securely."
            }
            return randomBytes
        }

        val decodedBytes = Base64.decode(passphraseB64, Base64.NO_WRAP)
        val utf8Bytes = passphraseB64.toByteArray(Charsets.UTF_8)

        val dbFile = context.getDatabasePath("floraguide.db")
        if (!dbFile.exists() || isPlaintext(dbFile)) {
            return decodedBytes
        }

        // Fast path: avoid expensive DB opening if we already proved which format works
        val cachedFormat = prefs.getString("sqlcipher_key_format", null)
        if (cachedFormat == "utf8") return utf8Bytes
        if (cachedFormat == "decoded") return decodedBytes

        // Probe formats to support legacy versions transparently, caching the result to prevent future UI lag
        return if (canOpenDatabase(dbFile, decodedBytes)) {
            prefs.edit().putString("sqlcipher_key_format", "decoded").apply()
            decodedBytes
        } else if (canOpenDatabase(dbFile, utf8Bytes)) {
            prefs.edit().putString("sqlcipher_key_format", "utf8").apply()
            utf8Bytes
        } else {
            // Fall back to standard; delegates corruption handling to native SQLite
            prefs.edit().putString("sqlcipher_key_format", "decoded").apply()
            decodedBytes
        }
    }

    fun convertPlaintextToEncrypted(context: Context, passphraseBytes: ByteArray) {
        val oldDb = context.getDatabasePath("floraguide.db")
        if (!oldDb.exists() || !isPlaintext(oldDb)) return

        val tmpDb = context.getDatabasePath("floraguide_enc.tmp")
        if (tmpDb.exists()) tmpDb.delete()
        tmpDb.parentFile?.mkdirs()

        // Pre-create the encrypted temporary database target
        SQLiteDatabase.openOrCreateDatabase(tmpDb.absolutePath, passphraseBytes, null, null).close()

        val hexKey = passphraseBytes.toHexString()

        SQLiteDatabase.openDatabase(oldDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { oldDbConn ->
            oldDbConn.rawExecSQL("PRAGMA cipher_plaintext_header_check = OFF;")
            oldDbConn.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
            // Attach the DB securely using a standard SQLite blob literal syntax
            oldDbConn.rawExecSQL("ATTACH DATABASE '${tmpDb.absolutePath}' AS encrypted KEY x'$hexKey';")
            oldDbConn.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            oldDbConn.rawExecSQL("DETACH DATABASE encrypted;")
        }

        check(oldDb.delete() && tmpDb.renameTo(oldDb)) {
            "Failed to replace old plaintext database with the encrypted version."
        }

        context.getDatabasePath("floraguide.db-wal").delete()
        context.getDatabasePath("floraguide.db-shm").delete()
    }

    // No fallback: deleting these prefs loses the only copy of the key, and plain prefs would store it
    // unencrypted. A Keystore error fails this start and leaves the key and the database intact.
    private fun getSecureSharedPreferences(context: Context): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "secure_db_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun isPlaintext(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() < 16) return false
        return dbFile.inputStream().use { stream ->
            val header = ByteArray(16)
            if (stream.read(header) != 16) false else String(header) == "SQLite format 3\u0000"
        }
    }

    private fun canOpenDatabase(dbFile: File, key: ByteArray): Boolean {
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, key, null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
                db.version // Trigger a data read to rigorously test decryption integrity
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
