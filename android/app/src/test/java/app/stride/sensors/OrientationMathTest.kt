package app.stride.sensors

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class OrientationMathTest {

    private val eps = 1e-6

    /** Builds the row-major device→world matrix from the device axes expressed in world (E, N, U). */
    private fun fromAxes(x: Vec3, y: Vec3, z: Vec3) = doubleArrayOf(
        x.x, y.x, z.x,
        x.y, y.y, z.y,
        x.z, y.z, z.z,
    )

    private val east = Vec3(1.0, 0.0, 0.0)
    private val west = Vec3(-1.0, 0.0, 0.0)
    private val north = Vec3(0.0, 1.0, 0.0)
    private val south = Vec3(0.0, -1.0, 0.0)
    private val up = Vec3(0.0, 0.0, 1.0)
    private val down = Vec3(0.0, 0.0, -1.0)

    private fun assertAngles(expected: CameraAngles, actual: CameraAngles, tol: Double = eps) {
        assertEquals("azimuth", 0.0, OrientationMath.angleDiff(expected.azimuth, actual.azimuth), tol)
        assertEquals("pitch", expected.pitch, actual.pitch, tol)
        assertEquals("roll", 0.0, OrientationMath.angleDiff(expected.roll, actual.roll), tol)
    }

    @Test
    fun portraitUprightFacingNorth() {
        // screen faces the user (south), top of the phone up → camera looks north
        val r = fromAxes(x = east, y = up, z = south)
        assertAngles(CameraAngles(0.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 0))
        assertEquals(0, OrientationMath.imageRotation(r, previous = 0))
    }

    @Test
    fun portraitUprightFacingEast() {
        val r = fromAxes(x = south, y = up, z = west)
        assertAngles(CameraAngles(90.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 0))
    }

    @Test
    fun portraitFacingSouthWest() {
        val s = sqrt(0.5)
        // camera looks SW, so the screen (+z) faces NE; x = y × z
        val z = Vec3(s, s, 0.0)
        val r = fromAxes(x = up.cross(z), y = up, z = z)
        assertAngles(CameraAngles(225.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 0))
    }

    @Test
    fun portraitTiltedUp30FacingNorth() {
        val a = Math.toRadians(30.0)
        val f = Vec3(0.0, cos(a), sin(a))
        val y = Vec3(0.0, -sin(a), cos(a))
        val r = fromAxes(x = east, y = y, z = f * -1.0)
        assertAngles(CameraAngles(0.0, 30.0, 0.0), OrientationMath.cameraAngles(r, 0))
    }

    @Test
    fun portraitTiltedDown45FacingWest() {
        val a = Math.toRadians(-45.0)
        val f = Vec3(-cos(a), 0.0, sin(a))
        val y = Vec3(sin(a), 0.0, cos(a)) // perpendicular to f, upward
        val z = f * -1.0
        val r = fromAxes(x = y.cross(z), y = y, z = z)
        assertAngles(CameraAngles(270.0, -45.0, 0.0), OrientationMath.cameraAngles(r, 0))
    }

    @Test
    fun portraitRolledClockwise10() {
        // photographer looks north and turns the phone 10° clockwise: the top tilts east
        val a = Math.toRadians(10.0)
        val y = Vec3(sin(a), 0.0, cos(a))
        val r = fromAxes(x = y.cross(south), y = y, z = south)
        assertAngles(CameraAngles(0.0, 0.0, 10.0), OrientationMath.cameraAngles(r, 0))
    }

    @Test
    fun landscapeTopLeftFacingNorth() {
        // phone turned 90° counter-clockwise: its right edge (+x) points up, top (+y) points west
        val r = fromAxes(x = up, y = west, z = south)
        assertEquals(90, OrientationMath.imageRotation(r, previous = 0))
        assertAngles(CameraAngles(0.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 90))
        // the same pose, judged against a portrait image, is rolled 90° counter-clockwise
        assertAngles(CameraAngles(0.0, 0.0, -90.0), OrientationMath.cameraAngles(r, 0))
    }

    @Test
    fun landscapeTopRightFacingEastTiltedUp20() {
        // phone turned clockwise: −x up, +y points south (right of an east-looking photographer)
        val a = Math.toRadians(20.0)
        val f = Vec3(cos(a), 0.0, sin(a))
        val imageUp = Vec3(-sin(a), 0.0, cos(a))
        val x = imageUp * -1.0
        val z = f * -1.0
        val r = fromAxes(x = x, y = z.cross(x), z = z)
        assertEquals(270, OrientationMath.imageRotation(r, previous = 0))
        assertAngles(CameraAngles(90.0, 20.0, 0.0), OrientationMath.cameraAngles(r, 270))
    }

    @Test
    fun upsideDownPortrait() {
        val r = fromAxes(x = west, y = down, z = south)
        assertEquals(180, OrientationMath.imageRotation(r, previous = 0))
        assertAngles(CameraAngles(0.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 180))
    }

    @Test
    fun rotationVectorIdentityIsFlatFaceUp() {
        // identity quaternion: device axes = world axes, phone lying flat, camera looks down
        val r = OrientationMath.rotationMatrixFromVector(floatArrayOf(0f, 0f, 0f, 1f))
        val angles = OrientationMath.cameraAngles(r, 0)
        assertEquals(-90.0, angles.pitch, eps)
        // flat phone keeps the previous rotation instead of guessing
        assertEquals(270, OrientationMath.imageRotation(r, previous = 270))
    }

    @Test
    fun rotationVectorUprightFacingNorth() {
        // rotation of +90° about east tilts the phone upright: y → up, z → south
        val h = Math.toRadians(45.0)
        val rv = floatArrayOf(sin(h).toFloat(), 0f, 0f, cos(h).toFloat())
        val r = OrientationMath.rotationMatrixFromVector(rv)
        assertAngles(CameraAngles(0.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 0), tol = 1e-3)
    }

    @Test
    fun rotationVectorUprightFacingEast() {
        // first stand the phone up (about east), then turn it 90° clockwise seen from above
        // (−90° about up): q = q_yaw ⊗ q_tilt
        val h = Math.toRadians(45.0)
        val tilt = doubleArrayOf(cos(h), sin(h), 0.0, 0.0) // w, x, y, z
        val yaw = doubleArrayOf(cos(-h), 0.0, 0.0, sin(-h))
        val q = mul(yaw, tilt)
        val rv = floatArrayOf(q[1].toFloat(), q[2].toFloat(), q[3].toFloat(), q[0].toFloat())
        val r = OrientationMath.rotationMatrixFromVector(rv)
        assertAngles(CameraAngles(90.0, 0.0, 0.0), OrientationMath.cameraAngles(r, 0), tol = 1e-3)
    }

    @Test
    fun imageRotationHysteresis() {
        // 50° towards landscape is still within the hysteresis band of portrait
        val a = Math.toRadians(50.0)
        val y = Vec3(-sin(a), 0.0, cos(a))
        val x = Vec3(cos(a), 0.0, sin(a))
        val r = fromAxes(x = x, y = y, z = x.cross(y))
        assertEquals(0, OrientationMath.imageRotation(r, previous = 0))
        val b = Math.toRadians(60.0)
        val x2 = Vec3(cos(b), 0.0, sin(b))
        val y2 = Vec3(-sin(b), 0.0, cos(b))
        assertEquals(90, OrientationMath.imageRotation(fromAxes(x2, y2, x2.cross(y2)), previous = 0))
    }

    @Test
    fun circularMeanAcrossNorth() {
        assertEquals(0.0, OrientationMath.angleDiff(OrientationMath.circularMean(listOf(350.0, 10.0)), 0.0), eps)
        assertEquals(355.0, OrientationMath.circularMean(listOf(350.0, 0.0)), eps)
    }

    @Test
    fun compassPoints() {
        assertEquals("N", OrientationMath.compassPoint(359.0))
        assertEquals("ESE", OrientationMath.compassPoint(112.5))
        assertEquals("W", OrientationMath.compassPoint(-90.0))
    }

    @Test
    fun opticsForTypicalPhone() {
        // 4.38 mm lens on a 5.64 × 4.23 mm sensor (≈ 1/2.55"), 4:3 output
        val s = SensorOptics(4.38, 5.64, 4.23, 4032, 3024, 4032, 3024)
        val landscape = OpticsMath.forImage(s, 4032, 3024)
        assertEquals(65.6, landscape.fovH, 0.1)
        assertEquals(51.5, landscape.fovV, 0.1)
        assertEquals(26.9, landscape.focalLength35mm, 0.1)
        val portrait = OpticsMath.forImage(s, 3024, 4032)
        assertEquals(landscape.fovV, portrait.fovH, eps)
        assertEquals(landscape.fovH, portrait.fovV, eps)
        // 16:9 crops the short side
        val wide = OpticsMath.forImage(s, 4032, 2268)
        assertEquals(landscape.fovH, wide.fovH, eps)
        assertEquals(true, wide.fovV < landscape.fovV)
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )
}
