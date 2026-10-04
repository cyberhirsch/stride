package app.stride.capture

import android.hardware.GeomagneticField
import android.location.Location
import android.os.Build
import android.os.SystemClock
import app.stride.BuildConfig
import app.stride.StrideApp
import app.stride.store.CaptureRecord
import app.stride.store.License
import app.stride.store.PhotoFields
import app.stride.store.UploadWorker
import app.stride.sensors.LocationTracker
import app.stride.sensors.OpticsMath
import app.stride.sensors.OrientationMath
import app.stride.sensors.OrientationReading
import app.stride.sensors.OrientationTracker
import app.stride.sensors.SensorOptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import kotlin.math.round

/** Sensor state frozen at the moment the shutter was pressed. */
data class ShotContext(
    val takenAtMillis: Long,
    val orientation: OrientationReading?,
    val location: Location,
    val optics: SensorOptics?,
    val title: String,
    val license: License,
)

/**
 * Turns a raw camera JPEG into a queued capture: stores it upright, computes optics for the
 * stored size, converts magnetic to true heading, writes EXIF/XMP and the JSON sidecar, and
 * schedules the upload.
 */
class CaptureProcessor(private val app: StrideApp) {

    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    suspend fun process(id: String, rawJpeg: File, shot: ShotContext): CaptureRecord = withContext(Dispatchers.IO) {
        val store = app.captures
        val target = store.imageFile(id)
        val (width, height) = JpegWriter.storeUpright(rawJpeg, target)
        rawJpeg.delete()

        val optics = shot.optics?.let { OpticsMath.forImage(it, width, height) }
        val loc = shot.location
        val declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            (if (loc.hasAltitude()) loc.altitude else 0.0).toFloat(), shot.takenAtMillis,
        ).declination.toDouble()

        val o = shot.orientation
        val heading = o?.let { OrientationMath.normalizeDegrees(it.angles.azimuth + declination) }
        val gpsAccuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val altitude = if (loc.hasAltitude()) loc.altitude else null
        val altitudeAccuracy = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null

        val sensors = buildJsonObject {
            put("app_version", BuildConfig.VERSION_NAME)
            put("heading_source", "rotation_vector")
            put("magnetic_declination", declination.r(3))
            put("calibration", o?.let { OrientationTracker.statusName(it.magnetometerStatus) } ?: "none")
            o?.let {
                put("heading_magnetic", it.angles.azimuth.r(2))
                put("heading_accuracy_source", it.accuracySource)
                put("image_rotation", it.imageRotation)
                put("orientation_age_ms", (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000)
                put("window_ms", OrientationTracker.SHOT_WINDOW_MS)
            }
            putJsonObject("location") {
                put("provider", loc.provider ?: "fused")
                put("fix_age_ms", LocationTracker.ageMillis(loc))
                put("fix_time", Instant.ofEpochMilli(loc.time).toString())
                if (loc.hasSpeed()) put("speed", loc.speed.toDouble().r(2))
                if (loc.hasBearing()) put("bearing", loc.bearing.toDouble().r(1))
            }
            shot.optics?.let {
                putJsonObject("optics") {
                    put("sensor_mm", "${it.physicalWidthMm}x${it.physicalHeightMm}")
                    put("pixel_array", "${it.pixelArrayWidth}x${it.pixelArrayHeight}")
                    put("active_array", "${it.activeWidth}x${it.activeHeight}")
                }
            }
            putJsonArray("samples") {
                o?.samples?.forEach { s ->
                    addJsonObject {
                        put("t", s.tMs)
                        put("az", s.azimuth)
                        put("pitch", s.pitch)
                        put("roll", s.roll)
                    }
                }
            }
        }

        val fields = PhotoFields(
            capturedAt = Instant.ofEpochMilli(shot.takenAtMillis).toString(),
            lat = loc.latitude,
            lon = loc.longitude,
            gpsAccuracy = gpsAccuracy?.r(2),
            altitude = altitude?.r(2),
            altitudeAccuracy = altitudeAccuracy?.r(2),
            heading = heading?.r(2),
            headingAccuracy = o?.headingAccuracy?.r(2),
            pitch = o?.angles?.pitch?.r(2),
            roll = o?.angles?.roll?.r(2),
            hasHeading = heading != null,
            hasTilt = o != null,
            focalLengthMm = optics?.focalLengthMm?.r(3),
            focalLength35mm = optics?.focalLength35mm?.r(1),
            fovH = optics?.fovH?.r(2),
            fovV = optics?.fovV?.r(2),
            width = width,
            height = height,
            device = device,
            sensors = sensors,
        )

        JpegWriter.writeMetadata(
            target,
            FileMetadata(
                capturedAtMillis = shot.takenAtMillis,
                lat = loc.latitude,
                lon = loc.longitude,
                altitude = altitude,
                gpsAccuracy = gpsAccuracy,
                gpsFixTimeMillis = loc.time,
                heading = fields.heading,
                headingAccuracy = fields.headingAccuracy,
                pitch = fields.pitch,
                roll = fields.roll,
                focalLengthMm = fields.focalLengthMm,
                focalLength35mm = fields.focalLength35mm,
                fovH = fields.fovH,
                fovV = fields.fovV,
                make = Build.MANUFACTURER,
                model = Build.MODEL,
                software = "Stride ${BuildConfig.VERSION_NAME}",
            ),
        )

        val record = CaptureRecord(
            id = id,
            createdAt = shot.takenAtMillis,
            title = shot.title.trim(),
            license = shot.license.value,
            photo = fields,
        )
        store.put(record)
        UploadWorker.enqueue(app, id)
        record
    }

    private fun Double.r(digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return round(this * f) / f
    }
}
