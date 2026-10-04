package app.stride.ui.map

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.stride.StrideApp
import app.stride.store.BBox
import app.stride.store.PhotoDetail
import app.stride.store.PhotoSummary
import app.stride.store.ReportReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StrideApp

    private val _photos = MutableStateFlow<List<PhotoSummary>>(emptyList())
    val photos: StateFlow<List<PhotoSummary>> = _photos.asStateFlow()

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Photo shown in the bottom sheet; null while loading or when closed. */
    var selected by mutableStateOf<PhotoDetail?>(null)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set

    /** Last camera, restored when the map tab is opened again. */
    var camera: CameraPosition? = null

    private var fetchJob: Job? = null

    /** Loads photos for the visible bounding box, debounced while the user pans. */
    fun onViewport(bbox: BBox) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            delay(300)
            loading = true
            try {
                _photos.value = app.api.photosInBBox(bbox)
                error = null
            } catch (e: Exception) {
                Log.w(TAG, "bbox query failed", e)
                error = e.message ?: "Could not load photos"
            } finally {
                loading = false
            }
        }
    }

    fun select(id: String) {
        selectedId = id
        selected = null
        viewModelScope.launch {
            try {
                val detail = app.api.photo(id)
                if (selectedId == id) selected = detail
            } catch (e: Exception) {
                error = e.message
                if (selectedId == id) selectedId = null
            }
        }
    }

    fun dismiss() {
        selectedId = null
        selected = null
    }

    suspend fun imageUrl(p: PhotoDetail, thumb: String): String =
        app.api.fileUrl(p.collectionId, p.id, p.image, thumb)

    /** Returns null on success, else an error message. */
    suspend fun report(photoId: String, reason: ReportReason, note: String): String? = try {
        val session = app.settings.current().session
        app.api.report(photoId, reason, note, session?.userId, session?.token)
        null
    } catch (e: Exception) {
        e.message ?: "Could not send report"
    }

    private companion object {
        const val TAG = "MapViewModel"
    }
}
