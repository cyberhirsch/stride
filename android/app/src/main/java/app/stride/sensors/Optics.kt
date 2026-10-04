package app.stride.sensors

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Camera intrinsics read from Camera2 characteristics (sizes in sensor orientation). */
data class SensorOptics(
    val focalLengthMm: Double,
    val physicalWidthMm: Double,
    val physicalHeightMm: Double,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val activeWidth: Int,
    val activeHeight: Int,
)

data class ImageOptics(
    val focalLengthMm: Double,
    val focalLength35mm: Double,
    val fovH: Double,
    val fovV: Double,
)

object OpticsMath {
    /** Diagonal of a 36 × 24 mm frame. */
    private val FULL_FRAME_DIAGONAL = hypot(36.0, 24.0)

    /**
     * Field of view of a stored image of [width] × [height] px (as displayed, i.e. after
     * rotation). The image is assumed to be the largest centred crop of the active pixel array
     * with the image's aspect ratio and no digital zoom, which is what CameraX produces for
     * still captures. The physical size reported by Camera2 covers the full pixel array, so it
     * is first scaled down to the active array.
     */
    fun forImage(s: SensorOptics, width: Int, height: Int): ImageOptics {
        val activeW = s.physicalWidthMm * s.activeWidth / s.pixelArrayWidth
        val activeH = s.physicalHeightMm * s.activeHeight / s.pixelArrayHeight
        val sensorLong = max(activeW, activeH)
        val sensorShort = min(activeW, activeH)
        val imageAspect = max(width, height).toDouble() / min(width, height)

        val (cropLong, cropShort) =
            if (sensorLong / sensorShort > imageAspect) sensorShort * imageAspect to sensorShort
            else sensorLong to sensorLong / imageAspect

        val f = s.focalLengthMm
        val fovLong = Math.toDegrees(2 * atan(cropLong / (2 * f)))
        val fovShort = Math.toDegrees(2 * atan(cropShort / (2 * f)))
        val cropFactor = FULL_FRAME_DIAGONAL / hypot(cropLong, cropShort)
        val landscape = width >= height
        return ImageOptics(
            focalLengthMm = f,
            focalLength35mm = f * cropFactor,
            fovH = if (landscape) fovLong else fovShort,
            fovV = if (landscape) fovShort else fovLong,
        )
    }
}
