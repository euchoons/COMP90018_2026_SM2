// File: app/src/main/java/au/edu/unimelb/floraguide/data/local/ObservationEntity.kt
package au.edu.unimelb.floraguide.data.local

import androidx.room.*

enum class SyncState { PENDING_UPLOAD, SYNCED, FAILED, PENDING_DELETE }

@Entity(tableName = "cached_observations")
data class ObservationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val speciesId: String,
    val scientificName: String,
    val commonName: String,
    val observedAtEpochMs: Long,
    val coarseLatitude: Double,
    val coarseLongitude: Double,
    val habitatName: String,
    val localPhotoPath: String?,
    val remotePhotoUrl: String?,
    val headingDegrees: Float?,
    val relativeScore: Double,
    val contextSource: String,
    val syncState: SyncState,
    val retryCount: Int = 0
)

@Dao
interface ObservationDao {
    @Query("SELECT * FROM cached_observations WHERE userId = :userId AND syncState != 'PENDING_DELETE' ORDER BY observedAtEpochMs DESC")
    fun getAllForUser(userId: String): List<ObservationEntity>

    @Query("SELECT * FROM cached_observations WHERE syncState IN ('PENDING_UPLOAD', 'PENDING_DELETE')")
    fun getPendingSync(): List<ObservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(observation: ObservationEntity)

    @Query("UPDATE cached_observations SET syncState = :state, remotePhotoUrl = :remoteUrl WHERE id = :id")
    fun updateSyncStatus(id: String, state: SyncState, remoteUrl: String?)

    @Query("DELETE FROM cached_observations WHERE id = :id")
    fun deletePermanently(id: String)
}
