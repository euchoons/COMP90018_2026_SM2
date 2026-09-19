package au.edu.unimelb.floraguide.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ObservationEntity::class, ObservationImport::class], version = 2, exportSchema = false)
abstract class FloraGuideDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao

    companion object {
        @Volatile private var instance: FloraGuideDatabase? = null

        fun getInstance(context: Context): FloraGuideDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FloraGuideDatabase::class.java, "floraguide.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
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
