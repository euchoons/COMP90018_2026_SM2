package au.edu.unimelb.floraguide.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FloraGuideMigrationTest {
    @Test fun `main version one keeps observations and pending deletes`() = migrate(false)
    @Test fun `PR23 version one keeps extra metadata without destructive fallback`() = migrate(true)

    private fun migrate(extraColumns: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val extra = if (extraColumns) "preferredMonthsCsv TEXT NOT NULL, habitatAffinityJson TEXT NOT NULL," else ""
                        db.execSQL("""
                            CREATE TABLE cached_observations (
                                id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, speciesId TEXT NOT NULL,
                                scientificName TEXT NOT NULL, commonName TEXT NOT NULL, $extra
                                observedAtEpochMs INTEGER NOT NULL, coarseLatitude REAL NOT NULL, coarseLongitude REAL NOT NULL,
                                habitatName TEXT NOT NULL, localPhotoPath TEXT, remotePhotoUrl TEXT, headingDegrees REAL,
                                relativeScore REAL NOT NULL, contextSource TEXT NOT NULL, syncState TEXT NOT NULL, retryCount INTEGER NOT NULL)
                        """.trimIndent())
                        val metadata = if (extraColumns) "'2,3', '{\"LAWN\":0.7}'," else ""
                        db.execSQL("""INSERT INTO cached_observations VALUES (
                            'one', 'A', 'blackwood', 'Acacia melanoxylon', 'Blackwood', $metadata
                            1000, -37.796, 144.961, 'LAWN', '/photo.jpg', NULL, 90, 0.8, 'ALA_LIVE', 'PENDING_DELETE', 2)
                        """.trimIndent())
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase
        helper.close()
        try {
            val db = Room.databaseBuilder(context, FloraGuideDatabase::class.java, name)
                .addMigrations(FloraGuideDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
            try {
                val dao = db.observationDao()
                val record = requireNotNull(dao.find("A", "one")) // Opens Room and validates the actual migrated schema.
                assertEquals(SyncState.PENDING_DELETE, record.syncState)
                assertEquals(2, record.retryCount)
                assertEquals("/photo.jpg", record.localPhotoPath)
                assertEquals(if (extraColumns) "2,3" else "", record.preferredMonthsCsv)
                assertEquals(if (extraColumns) "{\"LAWN\":0.7}" else "{}", record.habitatAffinityJson)
                assertEquals(0L, record.revision)
                assertFalse(dao.hasImported("preferences"))
                dao.saveLocal(cacheEntity("one", "B"))
                assertEquals("A", dao.find("A", "one")!!.userId)
                assertEquals("B", dao.find("B", "one")!!.userId)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
