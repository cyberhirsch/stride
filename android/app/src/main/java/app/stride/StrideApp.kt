package app.stride

import android.app.Application
import app.stride.capture.CaptureProcessor
import app.stride.store.ApiException
import app.stride.store.CaptureStore
import app.stride.store.PocketBase
import app.stride.store.SettingsRepository
import app.stride.store.UploadStatus
import app.stride.store.UploadWorker
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

/** Application object doubling as a tiny service locator. */
class StrideApp : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsRepository(this) }
    val api by lazy { PocketBase { settings.current().serverUrl } }
    val captures by lazy { CaptureStore(this) }
    val processor by lazy { CaptureProcessor(this) }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        scope.launch { captures.load() }
        scope.launch { refreshSession() }
    }

    /** Refreshes the stored token on start. Only an auth error logs out; offline keeps the session. */
    private suspend fun refreshSession() {
        val session = settings.current().session ?: return
        try {
            settings.saveSession(api.authRefresh(session.token))
        } catch (e: ApiException) {
            if (e.isAuthError) settings.clearSession()
        } catch (_: Exception) {
            // offline or server unreachable: keep the token, uploads retry later
        }
    }

    /** Re-queue captures that are not uploaded yet (e.g. after logging in again). */
    fun requeueUploads() {
        scope.launch {
            captures.load()
            captures.records.value
                .filter { it.status != UploadStatus.UPLOADED }
                .forEach { UploadWorker.enqueue(this@StrideApp, it.id, replace = it.status == UploadStatus.FAILED) }
        }
    }

    /**
     * Sets the title of a capture. Before upload only the sidecar changes; afterwards the
     * server record is patched too. Returns an error message, or null on success.
     */
    suspend fun editTitle(id: String, title: String): String? {
        val record = captures.update(id) { it.copy(title = title.trim()) } ?: return "Photo not found"
        val remoteId = record.remoteId
        if (record.status != UploadStatus.UPLOADED || remoteId == null) return null
        val session = settings.current().session ?: return "Log in to change the title"
        return try {
            api.updateTitle(session.token, remoteId, record.title)
            null
        } catch (e: Exception) {
            e.message ?: "Could not update the title"
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.http })) }
            .crossfade(true)
            .build()
}
