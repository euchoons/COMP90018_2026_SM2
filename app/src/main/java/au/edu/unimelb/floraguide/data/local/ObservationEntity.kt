package au.edu.unimelb.floraguide.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class SyncState { PENDING_UPLOAD, SYNCED, FAILED, PENDING_DELETE, DELETED }

@Entity(tableName = "cached_observations", primaryKeys = ["userId", "id"])
data class ObservationEntity(
    val id: String,
    val userId: String,
    val speciesId: String,
    val scientificName: String,
    val commonName: String,
    @ColumnInfo(defaultValue = "''") val preferredMonthsCsv: String = "",
    @ColumnInfo(defaultValue = "'{}'") val habitatAffinityJson: String = "{}",
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
    val retryCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    val observationJson: String? = null,
)

@Entity(tableName = "observation_imports")
data class ObservationImport(@PrimaryKey val name: String)

@Dao
interface ObservationDao {
    @Query("SELECT * FROM cached_observations WHERE userId = :userId AND syncState NOT IN ('PENDING_DELETE', 'DELETED') ORDER BY observedAtEpochMs DESC")
    suspend fun getAllForUser(userId: String): List<ObservationEntity>

    @Query("SELECT * FROM cached_observations WHERE userId = :userId AND syncState NOT IN ('PENDING_DELETE', 'DELETED') ORDER BY observedAtEpochMs DESC")
    fun observeForUser(userId: String): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM cached_observations WHERE userId = :userId AND id = :id")
    suspend fun find(userId: String, id: String): ObservationEntity?

    @Query("SELECT * FROM cached_observations WHERE userId = :userId AND syncState IN ('PENDING_UPLOAD', 'PENDING_DELETE')")
    suspend fun getPendingSync(userId: String): List<ObservationEntity>

    @Query("UPDATE cached_observations SET syncState = 'PENDING_UPLOAD', retryCount = 0, revision = revision + 1 WHERE userId = :userId AND syncState = 'FAILED'")
    suspend fun retryFailed(userId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(observation: ObservationEntity)

    @Transaction
    suspend fun saveLocal(observation: ObservationEntity) {
        val previous = find(observation.userId, observation.id)
        insertOrUpdate(observation.copy(revision = (previous?.revision ?: 0) + 1, retryCount = 0))
    }

    @Query("UPDATE cached_observations SET syncState = 'PENDING_DELETE', revision = revision + 1, retryCount = 0 WHERE userId = :userId AND id = :id AND syncState != 'DELETED'")
    suspend fun markDeleted(userId: String, id: String)

    @Query("UPDATE cached_observations SET syncState = 'SYNCED', remotePhotoUrl = :remoteUrl, retryCount = 0 WHERE userId = :userId AND id = :id AND syncState = 'PENDING_UPLOAD' AND revision = :revision")
    suspend fun acknowledgeUpload(userId: String, id: String, revision: Long, remoteUrl: String?): Int

    // ponytail: retain small tombstones so delayed cloud snapshots cannot resurrect a deletion.
    @Query("UPDATE cached_observations SET syncState = 'DELETED' WHERE userId = :userId AND id = :id AND syncState = 'PENDING_DELETE' AND revision = :revision")
    suspend fun acknowledgeDeletion(userId: String, id: String, revision: Long): Int

    @Query("UPDATE cached_observations SET retryCount = retryCount + 1, syncState = CASE WHEN retryCount >= 3 AND syncState = 'PENDING_UPLOAD' THEN 'FAILED' ELSE syncState END WHERE userId = :userId AND id = :id AND syncState = :expectedState AND revision = :revision")
    suspend fun recordFailure(userId: String, id: String, revision: Long, expectedState: SyncState): Int

    @Transaction
    suspend fun mergeRemote(observation: ObservationEntity) {
        val local = find(observation.userId, observation.id)
        if (local != null && (local.syncState != SyncState.SYNCED || local.revision > observation.revision)) return
        insertOrUpdate(observation.copy(localPhotoPath = local?.localPhotoPath, syncState = SyncState.SYNCED))
    }

    @Query("DELETE FROM cached_observations WHERE userId = :userId AND id = :id AND syncState = 'SYNCED' AND revision <= :revision")
    suspend fun removeRemote(userId: String, id: String, revision: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM observation_imports WHERE name = :name)")
    suspend fun hasImported(name: String): Boolean

    @Insert
    suspend fun recordImport(import: ObservationImport)

    @Transaction
    suspend fun importLegacy(observations: List<ObservationEntity>) {
        if (hasImported("preferences")) return
        observations.forEach { if (find(it.userId, it.id) == null) insertOrUpdate(it) }
        recordImport(ObservationImport("preferences"))
    }

    @Transaction
    suspend fun claimLocalGuest(userId: String) {
        require(userId != "anonymous_user")
        // Only never-authenticated, device-local rows are claimed. Firebase UIDs are not reassigned.
        getAllGuestRows().forEach {
            if (find(userId, it.id) == null) {
                insertOrUpdate(it.copy(userId = userId, revision = it.revision + 1))
            }
            // The chosen account already owning this ID means the local copy is a duplicate,
            // not an unclaimed record that a later account should inherit.
            deleteGuestRow(it.id)
        }
    }

    @Query("SELECT * FROM cached_observations WHERE userId = 'anonymous_user'")
    suspend fun getAllGuestRows(): List<ObservationEntity>

    @Query("DELETE FROM cached_observations WHERE userId = 'anonymous_user' AND id = :id")
    suspend fun deleteGuestRow(id: String)
}
