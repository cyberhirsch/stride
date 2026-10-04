package app.stride.store

import app.stride.capture.FileMetadata
import app.stride.capture.JpegWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFieldsTest {

    private val fields = PhotoFields(
        capturedAt = "2026-10-04T10:00:00.123Z",
        lat = 47.85, lon = 12.12,
        gpsAccuracy = 3.5,
        heading = 123.45, headingAccuracy = 8.0,
        pitch = 2.0, roll = -1.5,
        hasHeading = true, hasTilt = true,
        width = 3024, height = 4032,
        device = "Google Pixel 8",
        sensors = JsonObject(mapOf("app_version" to JsonPrimitive("0.1.0"))),
    )

    @Test
    fun formUsesApiFieldNamesAndOmitsUnknowns() {
        val form = fields.toForm()
        assertEquals("android", form["platform"])
        assertEquals("true", form["has_heading"])
        assertEquals("true", form["has_tilt"])
        assertEquals("123.45", form["heading"])
        assertEquals("2026-10-04T10:00:00.123Z", form["captured_at"])
        assertEquals("""{"app_version":"0.1.0"}""", form["sensors"])
        assertFalse("altitude unknown → not sent", "altitude" in form)
        assertFalse("fov unknown → not sent", "fov_h" in form)
    }

    @Test
    fun headingFlagsAlwaysSent() {
        val form = fields.copy(heading = null, pitch = null, roll = null, hasHeading = false, hasTilt = false).toForm()
        assertEquals("false", form["has_heading"])
        assertEquals("false", form["has_tilt"])
        assertFalse("heading" in form)
    }

    @Test
    fun sidecarRoundTrip() {
        val record = CaptureRecord(id = "1-abcd", createdAt = 1L, title = "Gate", photo = fields)
        val json = StrideJson.encodeToString(CaptureRecord.serializer(), record)
        assertEquals(record, StrideJson.decodeFromString(CaptureRecord.serializer(), json))
    }

    @Test
    fun xmpPacketHasStrideNamespace() {
        val xmp = JpegWriter.xmpPacket(
            FileMetadata(
                capturedAtMillis = 0, lat = 0.0, lon = 0.0, altitude = null, gpsAccuracy = null,
                gpsFixTimeMillis = 0, heading = 10.0, headingAccuracy = 5.0, pitch = 12.345, roll = -3.0,
                focalLengthMm = null, focalLength35mm = null, fovH = 65.5, fovV = null,
                make = "x", model = "y", software = "z",
            ),
        )
        assertTrue(xmp.contains("xmlns:stride=\"https://stride.app/ns/1.0/\""))
        assertTrue(xmp.contains("stride:Pitch=\"12.35\""))
        assertTrue(xmp.contains("stride:Roll=\"-3.00\""))
        assertTrue(xmp.contains("stride:HeadingAccuracy=\"5.00\""))
        assertTrue(xmp.contains("stride:FovH=\"65.50\""))
        assertFalse(xmp.contains("FovV"))
        assertTrue(xmp.all { it.code < 128 })
    }
}
