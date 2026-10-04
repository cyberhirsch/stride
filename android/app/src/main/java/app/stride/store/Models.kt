package app.stride.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val StrideJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

enum class License(val value: String, val label: String) {
    CC_BY("cc-by", "CC BY"),
    CC_BY_SA("cc-by-sa", "CC BY-SA"),
    CC0("cc0", "CC0"),
    ARR("arr", "All rights reserved");

    companion object {
        val DEFAULT = CC_BY
        fun of(value: String?): License = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

enum class ReportReason(val value: String, val label: String) {
    PERSON("person", "Shows a person"),
    PROPERTY("property", "Private property"),
    ABUSE("abuse", "Abusive content"),
    COPYRIGHT("copyright", "Copyright"),
    OTHER("other", "Other"),
}

// ---- PocketBase records ---------------------------------------------------------------

@Serializable
data class UserRecord(
    val id: String,
    val email: String? = null,
    val name: String? = null,
)

@Serializable
data class AuthResponse(val token: String, val record: UserRecord)

/** Row of the map query (see API.md "Map query"). */
@Serializable
data class PhotoSummary(
    val id: String,
    val collectionId: String = "",
    val image: String = "",
    val lat: Double,
    val lon: Double,
    val heading: Double? = null,
    @SerialName("has_heading") val hasHeading: Boolean = false,
    @SerialName("fov_h") val fovH: Double? = null,
    @SerialName("captured_at") val capturedAt: String = "",
    val title: String = "",
)

@Serializable
data class PhotoListResponse(val items: List<PhotoSummary> = emptyList())

@Serializable
data class PhotoDetail(
    val id: String,
    val collectionId: String = "",
    val image: String = "",
    val title: String = "",
    val license: String = "",
    val author: String = "",
    @SerialName("captured_at") val capturedAt: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val heading: Double? = null,
    @SerialName("has_heading") val hasHeading: Boolean = false,
    val pitch: Double? = null,
    val roll: Double? = null,
    @SerialName("has_tilt") val hasTilt: Boolean = false,
    @SerialName("heading_accuracy") val headingAccuracy: Double? = null,
    @SerialName("gps_accuracy") val gpsAccuracy: Double? = null,
    @SerialName("focal_length_35mm") val focalLength35mm: Double? = null,
    val device: String = "",
    val expand: Expand? = null,
) {
    @Serializable
    data class Expand(val author: UserRecord? = null)
}

@Serializable
data class PbError(val status: Int = 0, val message: String = "", val data: JsonObject? = null)

// ---- local capture queue --------------------------------------------------------------

@Serializable
enum class UploadStatus { PENDING, UPLOADING, UPLOADED, FAILED }

/** Form fields of a photo as sent to `POST /api/collections/photos/records`. */
@Serializable
data class PhotoFields(
    val capturedAt: String,
    val lat: Double,
    val lon: Double,
    val gpsAccuracy: Double? = null,
    val altitude: Double? = null,
    val altitudeAccuracy: Double? = null,
    val heading: Double? = null,
    val headingAccuracy: Double? = null,
    val pitch: Double? = null,
    val roll: Double? = null,
    val hasHeading: Boolean = false,
    val hasTilt: Boolean = false,
    val focalLengthMm: Double? = null,
    val focalLength35mm: Double? = null,
    val fovH: Double? = null,
    val fovV: Double? = null,
    val width: Int,
    val height: Int,
    val device: String,
    val platform: String = "android",
    val sensors: JsonObject,
) {
    /** Field name → value, omitting unknowns (PocketBase would store them as 0). */
    fun toForm(): Map<String, String> = buildMap {
        put("captured_at", capturedAt)
        put("lat", lat.toString())
        put("lon", lon.toString())
        gpsAccuracy?.let { put("gps_accuracy", it.toString()) }
        altitude?.let { put("altitude", it.toString()) }
        altitudeAccuracy?.let { put("altitude_accuracy", it.toString()) }
        heading?.let { put("heading", it.toString()) }
        headingAccuracy?.let { put("heading_accuracy", it.toString()) }
        pitch?.let { put("pitch", it.toString()) }
        roll?.let { put("roll", it.toString()) }
        put("has_heading", hasHeading.toString())
        put("has_tilt", hasTilt.toString())
        focalLengthMm?.let { put("focal_length_mm", it.toString()) }
        focalLength35mm?.let { put("focal_length_35mm", it.toString()) }
        fovH?.let { put("fov_h", it.toString()) }
        fovV?.let { put("fov_v", it.toString()) }
        put("width", width.toString())
        put("height", height.toString())
        put("device", device)
        put("platform", platform)
        put("sensors", sensors.toString())
    }
}

/** JSON sidecar stored next to each captured JPEG. */
@Serializable
data class CaptureRecord(
    val id: String,
    val createdAt: Long,
    val title: String = "",
    val license: String = License.DEFAULT.value,
    val status: UploadStatus = UploadStatus.PENDING,
    val error: String? = null,
    val remoteId: String? = null,
    val attempts: Int = 0,
    val photo: PhotoFields,
)
