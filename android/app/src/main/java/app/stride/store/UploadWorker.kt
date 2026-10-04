package app.stride.store

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stride.StrideApp
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Uploads one capture. Network errors and 5xx retry with exponential backoff. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = applicationContext as StrideApp
        val store = app.captures
        val record = store.get(id) ?: return Result.success() // deleted meanwhile
        if (record.status == UploadStatus.UPLOADED) return Result.success()

        val session = app.settings.current().session
        if (session == null) {
            store.update(id) { it.copy(status = UploadStatus.FAILED, error = "Log in to upload") }
            return Result.failure()
        }

        store.update(id) { it.copy(status = UploadStatus.UPLOADING, attempts = it.attempts + 1, error = null) }
        return try {
            val remoteId = app.api.uploadPhoto(
                token = session.token,
                authorId = session.userId,
                image = store.imageFile(id),
                title = record.title,
                license = record.license,
                fields = record.photo,
            )
            val done = store.update(id) { it.copy(status = UploadStatus.UPLOADED, remoteId = remoteId, error = null) }
            // title edited while the upload was running
            if (done != null && done.title != record.title) {
                runCatching { app.api.updateTitle(session.token, remoteId, done.title) }
            }
            Result.success()
        } catch (e: ApiException) {
            Log.w(TAG, "upload $id failed: ${e.status} ${e.message}")
            when {
                e.status >= 500 || e.status == 429 -> retryOrFail(store, id, e.message)
                e.isAuthError -> fail(store, id, "Session expired, log in again")
                else -> fail(store, id, e.message ?: "Rejected by server")
            }
        } catch (e: IOException) {
            Log.w(TAG, "upload $id: network error", e)
            retryOrFail(store, id, e.message ?: "Network error")
        } catch (e: IllegalArgumentException) {
            fail(store, id, "Invalid server URL") // HttpUrl parse failure
        }
    }

    private suspend fun retryOrFail(store: CaptureStore, id: String, message: String?): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            store.update(id) { it.copy(status = UploadStatus.PENDING, error = "Will retry: $message") }
            Result.retry()
        } else fail(store, id, message ?: "Upload failed")

    private suspend fun fail(store: CaptureStore, id: String, message: String): Result {
        store.update(id) { it.copy(status = UploadStatus.FAILED, error = message) }
        return Result.failure()
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val KEY_ID = "capture_id"
        private const val MAX_ATTEMPTS = 10

        /** Queue (or re-queue with [replace]) the upload of capture [id]. */
        fun enqueue(context: Context, id: String, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("upload")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload-$id",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("upload-$id")
        }
    }
}
