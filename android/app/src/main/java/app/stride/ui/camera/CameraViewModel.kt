package app.stride.ui.camera

import android.app.Application
import android.hardware.GeomagneticField
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.media.MediaActionSound
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.stride.StrideApp
import app.stride.capture.ShotContext
import app.stride.store.License
import app.stride.sensors.LocationTracker
import app.stride.sensors.OrientationTracker
import app.stride.sensors.SensorOptics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

sealed interface CameraEvent {
    data class Saved(val id: String) : CameraEvent
    data class Message(val text: String) : CameraEvent
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StrideApp

    val orientation = OrientationTracker(app)
    val location = LocationTracker(app)

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build(),
        )
        .build()

    private var sensorOptics: SensorOptics? = null
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    var title by mutableStateOf("")
    var license by mutableStateOf(License.DEFAULT)
        private set
    /** Captures still being written to disk. */
    var processing by mutableIntStateOf(0)
        private set

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var declinationCache: Pair<Location, Double>? = null

    init {
        viewModelScope.launch { license = app.settings.current().defaultLicense }
        // keep the capture rotation in step with how the phone is held (activity is portrait-locked)
        viewModelScope.launch {
            orientation.reading.collectLatest { r ->
                if (r != null) imageCapture.targetRotation = surfaceRotation(r.imageRotation)
            }
        }
    }

    fun startSensors(locationGranted: Boolean) {
        orientation.start()
        if (locationGranted) location.start()
    }

    fun stopSensors() {
        orientation.stop()
        location.stop()
    }

    fun chooseLicense(l: License) {
        license = l
        viewModelScope.launch { app.settings.setDefaultLicense(l) }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun onCameraBound(camera: Camera) {
        sensorOptics = runCatching {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (focal == null || phys == null || pixels == null || active == null) null
            else SensorOptics(
                focalLengthMm = focal.toDouble(),
                physicalWidthMm = phys.width.toDouble(),
                physicalHeightMm = phys.height.toDouble(),
                pixelArrayWidth = pixels.width,
                pixelArrayHeight = pixels.height,
                activeWidth = active.width(),
                activeHeight = active.height(),
            )
        }.onFailure { Log.w(TAG, "no camera characteristics", it) }.getOrNull()
    }

    /** Magnetic declination at [loc] (cached; it changes by < 0.1° over kilometres). */
    fun declination(loc: Location?): Double? {
        loc ?: return null
        declinationCache?.let { (l, d) -> if (l.distanceTo(loc) < 5_000) return d }
        val d = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            (if (loc.hasAltitude()) loc.altitude else 0.0).toFloat(), System.currentTimeMillis(),
        ).declination.toDouble()
        declinationCache = loc to d
        return d
    }

    fun shoot() {
        val loc = location.location.value
        if (loc == null) {
            _events.trySend(CameraEvent.Message("Waiting for a GPS fix"))
            return
        }
        val reading = orientation.snapshot()?.takeIf {
            SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 1_000_000_000L
        }
        reading?.let { imageCapture.targetRotation = surfaceRotation(it.imageRotation) }
        val shot = ShotContext(
            takenAtMillis = System.currentTimeMillis(),
            orientation = reading,
            location = loc,
            optics = sensorOptics,
            title = title,
            license = license,
        )
        val id = "%d-%04x".format(Locale.US, shot.takenAtMillis, Random.nextInt(0x10000))
        val raw = app.captures.tempFile(id)
        processing++
        sound.play(MediaActionSound.SHUTTER_CLICK)
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    viewModelScope.launch {
                        try {
                            app.processor.process(id, raw, shot)
                            _events.send(CameraEvent.Saved(id))
                        } catch (e: Exception) {
                            Log.e(TAG, "processing $id failed", e)
                            raw.delete()
                            _events.send(CameraEvent.Message("Could not save photo: ${e.message}"))
                        } finally {
                            processing--
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    processing--
                    raw.delete()
                    _events.trySend(CameraEvent.Message("Capture failed: ${exception.message}"))
                }
            },
        )
        title = ""
    }

    fun setTitle(id: String, title: String) {
        viewModelScope.launch {
            app.editTitle(id, title)?.let { _events.send(CameraEvent.Message(it)) }
        }
    }

    override fun onCleared() {
        stopSensors()
        sound.release()
    }

    companion object {
        private const val TAG = "CameraViewModel"

        fun surfaceRotation(degrees: Int) = when (degrees) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }
}
