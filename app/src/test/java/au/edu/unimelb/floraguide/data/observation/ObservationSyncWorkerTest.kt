// File: app/src/test/java/au/edu/unimelb/floraguide/data/observation/ObservationSyncWorkerTest.kt
package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObservationSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeWorkerDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeDao = FakeWorkerDao()
    }

    @Test
    fun `doWork returns success when no items are pending`() {
        val worker = TestListenableWorkerBuilder<TestableObservationSyncWorker>(context)
            .build()
        worker.daoMock = fakeDao

        val result = worker.startWork().get()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    class TestableObservationSyncWorker(
        appContext: Context,
        workerParams: WorkerParameters
    ) : ObservationSyncWorker(appContext, workerParams, FakeWorkerDao()) {
        lateinit var daoMock: ObservationDao
        override fun provideDao(): ObservationDao = daoMock
        override fun getUserId(): String? = "test-user-id"
    }

    private class FakeWorkerDao : ObservationDao {
        override fun getAllForUser(userId: String): List<ObservationEntity> = emptyList()
        override fun getPendingSync(): List<ObservationEntity> = emptyList()
        override fun insertOrUpdate(observation: ObservationEntity) = Unit
        override fun updateSyncStatus(id: String, state: SyncState, remoteUrl: String?) = Unit
        override fun deletePermanently(id: String) = Unit
    }
}
