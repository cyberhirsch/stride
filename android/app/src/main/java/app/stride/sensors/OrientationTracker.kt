package app.stride.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Smoothed orientation at one instant. Azimuth is magnetic; apply declination for true. */
data class OrientationReading(
    val angles: CameraAngles,
    /** Rotation that stores the image upright (0/90/180/270). */
    val imageRotation: Int,
    /** Estimated heading accuracy in degrees, null if unknown. */
    val headingAccuracy: Double?,
    /** Where [headingAccuracy] came from: "rotation_vector" or "status". */
    val accuracySource: String,
    /** Magnetometer calibration status (SensorManager.SENSOR_STATUS_*). */
    val magnetometerStatus: Int,
    /** Raw (unsmoothed) samples in the averaging window, for `sensors.samples`. */
    val samples: List<Sample>,
    val elapsedRealtimeNanos: Long,
) {
    val needsCalibration: Boolean
        get() = magnetometerStatus <= SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
            (headingAccuracy != null && headingAccuracy > 25.0)

    data class Sample(val tMs: Long, val azimuth: Double, val pitch: Double, val roll: Double)
}

/**
 * Listens to TYPE_ROTATION_VECTOR (fused gyro + accel + magnetometer, referenced to magnetic
 * north) and to the magnetometer's accuracy callbacks. Keeps a short history of rotation
 * matrices so a shot can be stamped with a smoothed reading: the optical-axis and image-up
 * vectors are averaged over the window (a vector mean is the circular mean of the angles and
 * cannot break at 0°/360°), then converted to angles.
 */
class OrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val available: Boolean get() = rotationSensor != null

    private class Entry(val tNanos: Long, val r: DoubleArray, val accuracyRad: Float)

    private val history = ArrayDeque<Entry>()
    private var imageRotation = 0
    // without a separate magnetometer there is nothing to calibrate or report
    private var magStatus =
        if (magnetometer != null) SensorManager.SENSOR_STATUS_UNRELIABLE else SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var rvStatus = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var lastPublish = 0L

    private val _reading = MutableStateFlow<OrientationReading?>(null)
    /** Published at ~10 Hz for the HUD. */
    val reading: StateFlow<OrientationReading?> = _reading.asStateFlow()

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(history) { history.clear() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(event)
            Sensor.TYPE_MAGNETIC_FIELD -> if (event.accuracy != magStatus) magStatus = event.accuracy
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        when (sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magStatus = accuracy
            Sensor.TYPE_ROTATION_VECTOR -> rvStatus = accuracy
        }
    }

    private fun onRotation(event: SensorEvent) {
        val r = OrientationMath.rotationMatrixFromVector(event.values)
        // values[4]: estimated heading accuracy in radians, −1 if unavailable
        val acc = if (event.values.size > 4) event.values[4] else -1f
        imageRotation = OrientationMath.imageRotation(r, imageRotation)
        synchronized(history) {
            history.addLast(Entry(event.timestamp, r, acc))
            while (history.size > 2 && event.timestamp - history.first().tNanos > HISTORY_NANOS) {
                history.removeFirst()
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublish >= 100) {
            lastPublish = now
            _reading.value = snapshot(HUD_WINDOW_MS)
        }
    }

    /** Smoothed reading over the last [windowMs]; null before the first sensor event. */
    fun snapshot(windowMs: Long = SHOT_WINDOW_MS): OrientationReading? {
        val entries = synchronized(history) {
            val last = history.lastOrNull() ?: return null
            history.filter { last.tNanos - it.tNanos <= windowMs * 1_000_000 }
        }
        val rotation = imageRotation
        var f = Vec3.ZERO
        var u = Vec3.ZERO
        val samples = ArrayList<OrientationReading.Sample>(entries.size)
        val t0 = entries.last().tNanos
        for (e in entries) {
            val fi = OrientationMath.opticalAxis(e.r)
            val ui = OrientationMath.imageUp(e.r, rotation)
            f += fi
            u += ui
            val a = OrientationMath.cameraAngles(fi, ui)
            samples += OrientationReading.Sample((e.tNanos - t0) / 1_000_000, a.azimuth.round(2), a.pitch.round(2), a.roll.round(2))
        }
        val accRad = entries.last().accuracyRad
        val (accuracy, source) =
            if (accRad >= 0f) Math.toDegrees(accRad.toDouble()) to "rotation_vector"
            else statusToDegrees(minOf(magStatus, rvStatus)) to "status"
        return OrientationReading(
            angles = OrientationMath.cameraAngles(f, u),
            imageRotation = rotation,
            headingAccuracy = accuracy,
            accuracySource = source,
            magnetometerStatus = magStatus,
            samples = samples,
            elapsedRealtimeNanos = t0,
        )
    }

    companion object {
        const val SHOT_WINDOW_MS = 250L
        private const val HUD_WINDOW_MS = 300L
        private const val HISTORY_NANOS = 1_000_000_000L

        /** Rough heading uncertainty when the sensor gives no estimate (heuristic). */
        fun statusToDegrees(status: Int): Double? = when (status) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 10.0
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 20.0
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 35.0
            else -> null
        }

        fun statusName(status: Int): String = when (status) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
            SensorManager.SENSOR_STATUS_NO_CONTACT -> "no_contact"
            else -> "unreliable"
        }
    }
}

internal fun Double.round(digits: Int): Double {
    val f = Math.pow(10.0, digits.toDouble())
    return Math.round(this * f) / f
}
