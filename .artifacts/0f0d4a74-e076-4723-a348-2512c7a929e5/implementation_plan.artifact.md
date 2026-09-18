# Fix App Crash on Launch and Lifecycle Issues

The app currently crashes shortly after launch due to several critical architectural issues:
1.  **Main Thread Database Access**: The app attempts to read and write to the Room database on the main thread, which triggers an `IllegalStateException`. This happens during `FloraGuideViewModel` initialization and in the Firestore sync listener.
2.  **WorkManager Worker Instantiation**: `ObservationSyncWorker` lacks a default constructor required by `WorkManager`, causing crashes when background sync is triggered.
3.  **Firestore Listener Threading**: The Firestore snapshot listener performs database operations directly in the callback, which runs on the main thread.

## Proposed Changes

### Domain Layer

#### [MODIFY] [Repositories.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/domain/repository/Repositories.kt)
- Make `ObservationRepository.loadAll()` and `save()` `suspend` functions to allow asynchronous execution.

### Data Layer

#### [MODIFY] [ObservationEntity.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/data/local/ObservationEntity.kt)
- Annotate `ObservationDao` methods with `suspend` so Room handles them on background threads.

#### [MODIFY] [OfflineFirstObservationRepository.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/data/observation/OfflineFirstObservationRepository.kt)
- Update `loadAll()` and `save()` to be `suspend` and use `withContext(Dispatchers.IO)`.
- Update `startRealtimeCloudSync` to launch database updates in a coroutine scope using `Dispatchers.IO`.

#### [MODIFY] [ObservationSyncWorker.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/data/observation/ObservationSyncWorker.kt)
- Add a secondary constructor `(Context, WorkerParameters)` that fetches dependencies from the `FloraGuideApplication` container. This allows `WorkManager` to instantiate the worker while maintaining testability through the primary constructor.

### UI Layer

#### [MODIFY] [FloraGuideViewModel.kt](file:///C:/Users/ec4pu/AndroidStudioProjects/COMP90018_2026_SM2official/app/src/main/java/au/edu/unimelb/floraguide/ui/FloraGuideViewModel.kt)
- Initialize `_uiState` with an empty list of observations.
- Load observations asynchronously in the `init` block using `viewModelScope.launch`.
- Update other calls to `loadAll()` and `save()` to be called within coroutines.

## Verification Plan

### Automated Tests
- Run existing unit tests for `OfflineFirstObservationRepository` and `FloraGuideViewModel` to ensure logic remains correct.
- `gradlew :app:testDebugUnitTest`

### Manual Verification
- Launch the app and verify it stays open and displays the home screen.
- Verify that observations are loaded from the database correctly.
- Trigger a sync (or wait for Firestore listener) and ensure no crashes occur.
