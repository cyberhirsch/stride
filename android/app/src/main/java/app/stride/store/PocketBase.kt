package app.stride.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Non-2xx response from PocketBase. Network failures surface as plain [IOException]s. */
class ApiException(val status: Int, message: String) : Exception(message) {
    val isAuthError get() = status == 401 || status == 403
}

data class BBox(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * Minimal PocketBase REST client for the endpoints in docs/API.md.
 * [baseUrl] is read on every call so a changed server URL applies immediately.
 */
class PocketBase(
    val http: OkHttpClient = defaultHttpClient(),
    private val baseUrl: suspend () -> String,
) {

    // ---- auth ----

    suspend fun login(email: String, password: String): AuthResponse {
        val body = buildJsonObject {
            put("identity", email)
            put("password", password)
        }
        return post("api/collections/users/auth-with-password", body.toString().toRequestBody(JSON))
    }

    suspend fun register(email: String, password: String, name: String): UserRecord {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("passwordConfirm", password)
            put("name", name)
        }
        return post("api/collections/users/records", body.toString().toRequestBody(JSON))
    }

    suspend fun authRefresh(token: String): AuthResponse =
        post("api/collections/users/auth-refresh", ByteArray(0).toRequestBody(JSON), token)

    // ---- photos ----

    suspend fun photosInBBox(b: BBox): List<PhotoSummary> {
        val lonFilter = if (b.west <= b.east) "lon>=${b.west.q()} && lon<=${b.east.q()}"
        else "(lon>=${b.west.q()} || lon<=${b.east.q()})" // bbox crosses the antimeridian
        val url = url("api/collections/photos/records").newBuilder()
            .addQueryParameter("filter", "(lat>=${b.south.q()} && lat<=${b.north.q()} && $lonFilter)")
            .addQueryParameter("sort", "-captured_at")
            .addQueryParameter("perPage", "500")
            .addQueryParameter("skipTotal", "1")
            .addQueryParameter("fields", "id,collectionId,image,lat,lon,heading,has_heading,fov_h,captured_at,title")
            .build()
        return execute<PhotoListResponse>(Request.Builder().url(url).get().build()).items
    }

    suspend fun photo(id: String): PhotoDetail {
        val url = url("api/collections/photos/records/$id").newBuilder()
            .addQueryParameter("expand", "author")
            .build()
        return execute(Request.Builder().url(url).get().build())
    }

    /** Creates the photo record; returns the new record id. */
    suspend fun uploadPhoto(token: String, authorId: String, image: File, title: String, license: String, fields: PhotoFields): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("author", authorId)
            .addFormDataPart("license", license)
            .addFormDataPart("title", title)
        fields.toForm().forEach { (k, v) -> form.addFormDataPart(k, v) }
        form.addFormDataPart("image", image.name, image.asRequestBody(JPEG))
        val created: PhotoDetail = post("api/collections/photos/records", form.build(), token)
        return created.id
    }

    suspend fun updateTitle(token: String, id: String, title: String) {
        val body = buildJsonObject { put("title", title) }.toString().toRequestBody(JSON)
        execute<PhotoDetail>(
            Request.Builder().url(url("api/collections/photos/records/$id"))
                .patch(body).header("Authorization", token).build(),
        )
    }

    suspend fun report(photoId: String, reason: ReportReason, note: String, reporterId: String?, token: String?) {
        val body = buildJsonObject {
            put("photo", photoId)
            put("reason", reason.value)
            put("note", note)
            if (reporterId != null) put("reporter", reporterId)
        }
        post<kotlinx.serialization.json.JsonObject>("api/collections/reports/records", body.toString().toRequestBody(JSON), token)
    }

    // ---- URLs ----

    suspend fun fileUrl(collectionId: String, recordId: String, fileName: String, thumb: String? = null): String {
        val b = url("api/files/$collectionId/$recordId/$fileName").newBuilder()
        if (thumb != null) b.addQueryParameter("thumb", thumb)
        return b.build().toString()
    }

    // ---- plumbing ----

    private suspend fun url(path: String): HttpUrl = (baseUrl().trimEnd('/') + "/" + path).toHttpUrl()

    private suspend inline fun <reified T> post(path: String, body: RequestBody, token: String? = null): T {
        val request = Request.Builder().url(url(path)).post(body)
        if (token != null) request.header("Authorization", token)
        return execute(request.build())
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(response.code, text))
            StrideJson.decodeFromString<T>(text)
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val err = runCatching { StrideJson.decodeFromString<PbError>(body) }.getOrNull()
        val fieldErrors = err?.data?.entries?.joinToString("; ") { (field, v) ->
            val msg = runCatching {
                (v as kotlinx.serialization.json.JsonObject)["message"].toString().trim('"')
            }.getOrDefault("invalid")
            "$field: $msg"
        }
        return listOfNotNull(err?.message?.ifBlank { null } ?: "HTTP $code", fieldErrors?.ifBlank { null })
            .joinToString(" — ")
    }

    private fun Double.q() = String.format(Locale.US, "%.6f", this)

    companion object {
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
