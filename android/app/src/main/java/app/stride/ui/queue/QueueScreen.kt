package app.stride.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stride.StrideApp
import app.stride.store.CaptureRecord
import app.stride.store.License
import app.stride.store.UploadStatus
import app.stride.store.UploadWorker
import app.stride.ui.camera.TitleDialog
import app.stride.ui.theme.WarnRed
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen() {
    val app = LocalContext.current.applicationContext as StrideApp
    val records by app.captures.records.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<CaptureRecord?>(null) }
    var deleting by remember { mutableStateOf<CaptureRecord?>(null) }
    var clearing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Uploads") },
                actions = {
                    if (records.any { it.status == UploadStatus.UPLOADED }) {
                        TextButton(onClick = { clearing = true }) { Text("Clear uploaded") }
                    }
                    IconButton(onClick = { app.requeueUploads() }) { Icon(Icons.Filled.Refresh, "Retry all") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No photos yet. Captured photos wait here until they are uploaded.")
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(records, key = { it.id }) { r ->
                CaptureRow(
                    record = r,
                    onRetry = { UploadWorker.enqueue(app, r.id, replace = true) },
                    onEdit = { editing = r },
                    onDelete = { deleting = r },
                )
            }
        }
    }

    editing?.let { r ->
        TitleDialog(
            initial = r.title,
            onDismiss = { editing = null },
            onSave = { title ->
                editing = null
                scope.launch { app.editTitle(r.id, title)?.let { snackbar.showSnackbar(it) } }
            },
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove from phone?") },
            text = {
                Text(
                    if (r.status == UploadStatus.UPLOADED) "The uploaded photo stays on the server."
                    else "This photo has not been uploaded and will be lost.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    UploadWorker.cancel(app, r.id)
                    scope.launch { app.captures.delete(r.id) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear uploaded photos?") },
            text = { Text("Removes the local copies of photos that are already on the server.") },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    scope.launch {
                        records.filter { it.status == UploadStatus.UPLOADED }.forEach { app.captures.delete(it.id) }
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CaptureRow(record: CaptureRecord, onRetry: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as StrideApp
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(app.captures.imageFile(record.id))
                    .size(Size(256, 256))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val p = record.photo
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.createdAt)) +
                        " · " + License.of(record.license).label,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        append("%.5f, %.5f".format(Locale.US, p.lat, p.lon))
                        p.heading?.let { append(" · %.0f°".format(Locale.US, it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                StatusLine(record)
            }
            Column {
                if (record.status == UploadStatus.FAILED) {
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Retry upload") }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit title") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
    }
}

@Composable
private fun StatusLine(record: CaptureRecord) {
    when (record.status) {
        UploadStatus.PENDING -> Text(
            record.error ?: "Waiting for network",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 2,
        )
        UploadStatus.UPLOADING -> {
            Text("Uploading…", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        UploadStatus.UPLOADED -> Text(
            "Uploaded", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        UploadStatus.FAILED -> Text(
            "Failed: ${record.error ?: "unknown error"}",
            style = MaterialTheme.typography.labelMedium, color = WarnRed, maxLines = 3,
        )
    }
}
