package app.stride.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything that goes into the file's EXIF / XMP (see API.md "Metadata in the file"). */
data class FileMetadata(
    val capturedAtMillis: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val gpsAccuracy: Double?,
    val gpsFixTimeMillis: Long,
    val heading: Double?,
    val headingAccuracy: Double?,
    val pitch: Double?,
    val roll: Double?,
    val focalLengthMm: Double?,
    val focalLength35mm: Double?,
    val fovH: Double?,
    val fovV: Double?,
    val make: String,
    val model: String,
    val software: String,
)

object JpegWriter {
    private const val TAG = "JpegWriter"
    const val STRIDE_NS = "https://stride.app/ns/1.0/"

    /** Camera-written tags worth keeping when the pixels are re-encoded. */
    private val KEEP_TAGS = listOf(
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
    )

    /**
     * Writes [source] (a camera JPEG, possibly with an EXIF rotation) to [target] with pixels
     * stored upright (Orientation = 1). Returns the stored width and height.
     * If the bitmap does not fit in memory, the pixels are copied as-is and the EXIF
     * Orientation is kept; the returned size is then the displayed (rotated) size.
     */
    fun storeUpright(source: File, target: File): Pair<Int, Int> {
        val srcExif = ExifInterface(source)
        val rotation = srcExif.rotationDegrees
        val flipped = srcExif.isFlipped
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)

        if (rotation == 0 && !flipped) {
            source.copyTo(target, overwrite = true)
            return bounds.outWidth to bounds.outHeight
        }
        try {
            val bitmap = BitmapFactory.decodeFile(source.path) ?: error("cannot decode ${source.name}")
            val m = Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                postRotate(rotation.toFloat())
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (upright !== bitmap) bitmap.recycle()
            target.outputStream().buffered().use { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val size = upright.width to upright.height
            upright.recycle()
            copyTags(srcExif, ExifInterface(target))
            return size
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "not enough memory to rotate, keeping EXIF orientation", oom)
            source.copyTo(target, overwrite = true)
            return if (rotation % 180 != 0) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        }
    }

    private fun copyTags(from: ExifInterface, to: ExifInterface) {
        for (tag in KEEP_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        to.saveAttributes()
    }

    /** Writes position, direction, time, optics and the Stride XMP packet into [file]. */
    fun writeMetadata(file: File, m: FileMetadata) {
        val exif = ExifInterface(file)
        val keepOrientation = exif.rotationDegrees != 0 // only after the OOM fallback

        exif.setAttribute(ExifInterface.TAG_MAKE, m.make)
        exif.setAttribute(ExifInterface.TAG_MODEL, m.model)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, m.software)
        if (!keepOrientation) exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())

        // time: local wall clock + offset, sub-seconds
        val instant = Instant.ofEpochMilli(m.capturedAtMillis)
        val local = instant.atZone(ZoneId.systemDefault())
        val dateTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US).format(local)
        val offset = DateTimeFormatter.ofPattern("xxx", Locale.US).format(local)
        val subSec = "%03d".format(Locale.US, m.capturedAtMillis % 1000)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec)

        // position
        exif.setLatLong(m.lat, m.lon)
        m.altitude?.let { exif.setAltitude(it) }
        m.gpsAccuracy?.let { exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, rational(it, 100)) }
        val gpsTime = Instant.ofEpochMilli(m.gpsFixTimeMillis).atZone(ZoneOffset.UTC)
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US).format(gpsTime))
        exif.setAttribute(
            ExifInterface.TAG_GPS_TIMESTAMP,
            "${gpsTime.hour}/1,${gpsTime.minute}/1,${gpsTime.second * 1000 + gpsTime.nano / 1_000_000}/1000",
        )
        exif.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, "WGS-84")

        // direction of the optical axis, true north
        m.heading?.let {
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, rational(it, 100))
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.GPS_DIRECTION_TRUE)
        }

        // optics
        m.focalLengthMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, rational(it, 1000)) }
        m.focalLength35mm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.roundToInt().toString()) }

        exif.setAttribute(ExifInterface.TAG_XMP, xmpPacket(m))
        exif.saveAttributes()
    }

    /** XMP packet stored in IFD0 tag 700. ExifInterface writes it as ASCII bytes, so the packet
     *  uses the empty `begin` attribute (= UTF-8) instead of a U+FEFF byte-order mark. */
    fun xmpPacket(m: FileMetadata): String {
        val attrs = buildList {
            m.pitch?.let { add("stride:Pitch=\"${num(it)}\"") }
            m.roll?.let { add("stride:Roll=\"${num(it)}\"") }
            m.headingAccuracy?.let { add("stride:HeadingAccuracy=\"${num(it)}\"") }
            m.fovH?.let { add("stride:FovH=\"${num(it)}\"") }
            m.fovV?.let { add("stride:FovV=\"${num(it)}\"") }
        }.joinToString("\n    ")
        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:stride="$STRIDE_NS"
    $attrs/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun num(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun rational(v: Double, denominator: Int) = "${(abs(v) * denominator).roundToInt()}/$denominator"
}
