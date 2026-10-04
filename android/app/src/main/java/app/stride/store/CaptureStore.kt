package app.stride.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Captured photos waiting for (or done with) upload: `files/captures/<id>.jpg` plus a JSON
 * sidecar `<id>.json` holding the metadata and upload state. Files survive restarts, so
 * capturing works offline and WorkManager uploads later.
 */
class CaptureStore(context: Context) {

    private val dir = File(context.filesDir, "captures").apply { mkdirs() }
    private val mutex = Mutex()

    private val _records = MutableStateFlow<List<CaptureRecord>>(emptyList())
    /** All captures, newest first. */
    val records: StateFlow<List<CaptureRecord>> = _records.asStateFlow()

    fun imageFile(id: String) = File(dir, "$id.jpg")
    private fun sidecar(id: String) = File(dir, "$id.json")

    /** Scratch file for a capture still being processed (not listed). */
    fun tempFile(id: String) = File(dir, "$id.part")

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { reload() }
    }

    suspend fun get(id: String): CaptureRecord? = withContext(Dispatchers.IO) {
        mutex.withLock { read(id) }
    }

    suspend fun put(record: CaptureRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(record)
            reload()
        }
    }

    /** Read-modify-write of one sidecar; returns the updated record or null if it is gone. */
    suspend fun update(id: String, change: (CaptureRecord) -> CaptureRecord): CaptureRecord? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = read(id)?.let(change) ?: return@withLock null
                write(updated)
                reload()
                updated
            }
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            imageFile(id).delete()
            sidecar(id).delete()
            reload()
        }
    }

    private fun read(id: String): CaptureRecord? = runCatching {
        StrideJson.decodeFromString<CaptureRecord>(sidecar(id).readText())
    }.getOrNull()

    private fun write(record: CaptureRecord) {
        val tmp = File(dir, "${record.id}.json.tmp")
        tmp.writeText(StrideJson.encodeToString(CaptureRecord.serializer(), record))
        if (!tmp.renameTo(sidecar(record.id))) {
            sidecar(record.id).delete()
            tmp.renameTo(sidecar(record.id))
        }
    }

    private fun reload() {
        val list = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { StrideJson.decodeFromString<CaptureRecord>(f.readText()) }
                .onFailure { Log.w(TAG, "bad sidecar ${f.name}", it) }
                .getOrNull()
                ?.takeIf { imageFile(it.id).exists() }
        }
        _records.value = list.sortedByDescending { it.createdAt }
    }

    private companion object {
        const val TAG = "CaptureStore"
    }
}
