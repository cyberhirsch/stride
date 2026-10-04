package app.stride.sensors

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Orientation of the camera's optical axis, all in degrees. Azimuth is relative to the
 *  same north the rotation matrix uses (magnetic for TYPE_ROTATION_VECTOR). */
data class CameraAngles(val azimuth: Double, val pitch: Double, val roll: Double)

/**
 * Pure orientation math, free of Android dependencies so it can be unit tested on the JVM.
 *
 * ## Frames
 * - Device frame (Android sensor convention, fixed to the hardware, independent of screen
 *   rotation): +x to the right of the screen, +y to the top of the screen in natural
 *   (portrait) orientation, +z out of the screen towards the user.
 * - World frame (as produced by `SensorManager.getRotationMatrixFromVector`): X = East,
 *   Y = North, Z = Up.
 * - `r` is the 3x3 rotation matrix, row-major, mapping device to world: v_world = R · v_device.
 *   Column j of R is device axis j expressed in world coordinates; row 2 of R is world Up
 *   expressed in device coordinates.
 *
 * ## Optical axis, heading, pitch
 * The back camera looks along device −z, so in the world frame the optical axis is
 * f = −(third column) = (−R[2], −R[5], −R[8]) = (E, N, U).
 *   heading (azimuth) = atan2(E, N)   (clockwise from north)
 *   pitch            = asin(U)        (+ above the horizon)
 * This uses the matrix directly instead of `SensorManager.getOrientation` (which describes
 * the device's y axis and degenerates exactly when a phone is held upright to take a photo),
 * so it is equally valid in portrait, landscape and near the horizon. Heading is undefined
 * only when the camera points straight up or down (|pitch| → 90°).
 *
 * ## Roll
 * Roll is measured about the optical axis relative to the *stored* image's up direction
 * (API.md: 0 = upright as stored, + = clockwise as seen by the photographer).
 * The image's up direction in device coordinates depends on the rotation the image is
 * stored with (see [imageRotation]): 0° → +y, 90° → +x, 180° → −y, 270° → −x, i.e.
 * u_dev = (sin k, cos k, 0). In the world frame u = R · u_dev.
 * The reference "up" r is world Up projected onto the image plane:
 *   r = Z − (Z·f) f, normalised.
 * The photographer's right is  s = f × r  (forward × up = right in a right-handed frame:
 * N × Up = East). Turning the camera clockwise (as the photographer sees it) tilts the
 * image's up vector towards s, so
 *   roll = atan2(u·s, u·r).
 * When looking straight up/down r vanishes; north projected onto the image plane is used
 * as the reference instead (roll is then "relative to north", which is the best available).
 */
object OrientationMath {

    /** Same as `SensorManager.getRotationMatrixFromVector` (3x3 output), re-implemented so the
     *  full sensor-to-angles path can be tested without Android. */
    fun rotationMatrixFromVector(rv: FloatArray): DoubleArray {
        val q1 = rv[0].toDouble()
        val q2 = rv[1].toDouble()
        val q3 = rv[2].toDouble()
        val q0 = if (rv.size >= 4) rv[3].toDouble() else {
            val t = 1 - q1 * q1 - q2 * q2 - q3 * q3
            if (t > 0) sqrt(t) else 0.0
        }
        val sqQ1 = 2 * q1 * q1
        val sqQ2 = 2 * q2 * q2
        val sqQ3 = 2 * q3 * q3
        val q1q2 = 2 * q1 * q2
        val q3q0 = 2 * q3 * q0
        val q1q3 = 2 * q1 * q3
        val q2q0 = 2 * q2 * q0
        val q2q3 = 2 * q2 * q3
        val q1q0 = 2 * q1 * q0
        return doubleArrayOf(
            1 - sqQ2 - sqQ3, q1q2 - q3q0, q1q3 + q2q0,
            q1q2 + q3q0, 1 - sqQ1 - sqQ3, q2q3 - q1q0,
            q1q3 - q2q0, q2q3 + q1q0, 1 - sqQ1 - sqQ2,
        )
    }

    /** Optical axis (−z of the device) in world coordinates (E, N, U). */
    fun opticalAxis(r: DoubleArray) = Vec3(-r[2], -r[5], -r[8])

    /** Up direction of an image stored with [rotation] degrees, in world coordinates. */
    fun imageUp(r: DoubleArray, rotation: Int): Vec3 {
        val k = Math.toRadians(rotation.toDouble())
        val ux = sin(k)
        val uy = cos(k)
        return Vec3(r[0] * ux + r[1] * uy, r[3] * ux + r[4] * uy, r[6] * ux + r[7] * uy)
    }

    fun cameraAngles(r: DoubleArray, rotation: Int): CameraAngles =
        cameraAngles(opticalAxis(r), imageUp(r, rotation))

    /** Angles from the optical axis [forward] and the image-up vector [up], both in world
     *  coordinates (need not be normalised; averaged vectors are fine). */
    fun cameraAngles(forward: Vec3, up: Vec3): CameraAngles {
        val f = forward.normalized()
        val pitch = Math.toDegrees(asin(f.z.coerceIn(-1.0, 1.0)))
        val azimuth = normalizeDegrees(Math.toDegrees(atan2(f.x, f.y)))

        var ref = Vec3(0.0, 0.0, 1.0) - f * f.z
        if (ref.length() < 1e-3) ref = Vec3(0.0, 1.0, 0.0) - f * f.y
        ref = ref.normalized()
        val right = f.cross(ref)
        val roll = Math.toDegrees(atan2(up.dot(right), up.dot(ref)))
        return CameraAngles(azimuth, pitch, roll)
    }

    /**
     * Rotation (0/90/180/270, same meaning as `Surface.ROTATION_*` × 90) that makes the image
     * upright, chosen from gravity: the device edge pointing most "up" becomes the image top.
     * World Up in device coordinates is row 2 of R. Keeps [previous] while the phone is close
     * to flat (camera pointing steeply up/down) and within a ±[hysteresis]° band around
     * the boundaries, so the choice does not flicker.
     */
    fun imageRotation(r: DoubleArray, previous: Int, hysteresis: Double = 10.0): Int {
        val gx = r[6]
        val gy = r[7]
        if (hypot(gx, gy) < 0.35) return previous
        // angle of world-up within the screen plane: +y → 0°, +x → 90°, −y → 180°, −x → 270°
        val angle = normalizeDegrees(Math.toDegrees(atan2(gx, gy)))
        if (abs(angleDiff(angle, previous.toDouble())) <= 45 + hysteresis) return previous
        return (Math.round(angle / 90.0).toInt() % 4) * 90
    }

    fun normalizeDegrees(d: Double): Double {
        val m = d % 360.0
        return if (m < 0) m + 360.0 else m
    }

    /** Signed smallest difference a − b in (−180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = normalizeDegrees(a - b)
        if (d > 180) d -= 360
        return d
    }

    /** Circular mean of angles in degrees, result in [0, 360). */
    fun circularMean(degrees: Collection<Double>): Double {
        var s = 0.0
        var c = 0.0
        for (d in degrees) {
            val rad = Math.toRadians(d)
            s += sin(rad)
            c += cos(rad)
        }
        return normalizeDegrees(Math.toDegrees(atan2(s, c)))
    }

    private val POINTS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compassPoint(degrees: Double): String =
        POINTS[(Math.round(normalizeDegrees(degrees) / 22.5).toInt()) % 16]
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l == 0.0) this else Vec3(x / l, y / l, z / l)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
