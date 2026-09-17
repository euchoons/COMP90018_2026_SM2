package au.edu.unimelb.floraguide.domain

import org.junit.Test

class IdentifyStoredPhotoUseCaseTest {
    @Test fun successUsesDownloadedFile() = IdentifyStoredPhotoContract.successUsesDownloadedFile()
    @Test fun uploadFailureStopsThePipeline() = IdentifyStoredPhotoContract.uploadFailureStopsThePipeline()
    @Test fun downloadFailureDoesNotClassify() = IdentifyStoredPhotoContract.downloadFailureDoesNotClassify()
    @Test fun classificationFailureCleansTheCache() = IdentifyStoredPhotoContract.classificationFailureCleansTheCache()
    @Test fun retryReusesTheCloudObject() = IdentifyStoredPhotoContract.retryReusesTheCloudObject()
    @Test fun emptyResponseIsAnError() = IdentifyStoredPhotoContract.emptyResponseIsAnError()
    @Test fun cancellationIsNotWrappedAsFailure() = IdentifyStoredPhotoContract.cancellationIsNotWrappedAsFailure()
    @Test fun cancellingAnActiveClassificationCleansTheCache() = IdentifyStoredPhotoContract.cancellingAnActiveClassificationCleansTheCache()
}
