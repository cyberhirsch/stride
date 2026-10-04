package app.stride.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stride.store.License
import app.stride.store.PhotoDetail
import app.stride.store.ReportReason
import app.stride.store.SettingsState
import app.stride.sensors.OrientationMath
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSheet(vm: MapViewModel, settings: SettingsState) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fullScreen by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var thanks by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = vm::dismiss, sheetState = sheetState) {
        val p = vm.selected
        if (p == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        var thumb by remember(p.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(p.id, settings.serverUrl) { thumb = vm.imageUrl(p, "400x0") }

        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            AsyncImage(
                model = thumb,
                contentDescription = p.title.ifBlank { "Photo" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.DarkGray)
                    .clickable { fullScreen = true },
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge)
                    val author = p.expand?.author?.name?.ifBlank { null } ?: "Unknown author"
                    Text("$author · ${formatDate(p.capturedAt)}", style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { reporting = true }) { Icon(Icons.Filled.Flag, "Report") }
            }
            Details(p)
            thanks?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
        }
    }

    vm.selected?.let { p ->
        if (fullScreen) FullScreenImage(vm, p) { fullScreen = false }
        if (reporting) ReportDialog(
            onDismiss = { reporting = false },
            onSend = { reason, note ->
                reporting = false
                thanks = "Sending report…"
                scope.launch { thanks = vm.report(p.id, reason, note) ?: "Thanks, the report was sent." }
            },
        )
    }
}

@Composable
private fun Details(p: PhotoDetail) {
    val lines = buildList {
        add("Licence" to License.of(p.license).label)
        if (p.hasHeading && p.heading != null) {
            val acc = p.headingAccuracy?.let { " ±%.0f°".format(Locale.US, it) } ?: ""
            add("Heading" to "%.0f° %s%s".format(Locale.US, p.heading, OrientationMath.compassPoint(p.heading), acc))
        }
        if (p.hasTilt) {
            add("Pitch / roll" to "%+.1f° / %+.1f°".format(Locale.US, p.pitch ?: 0.0, p.roll ?: 0.0))
        }
        add("Position" to "%.5f, %.5f".format(Locale.US, p.lat, p.lon) + (p.gpsAccuracy?.let { " ±%.0f m".format(Locale.US, it) } ?: ""))
        p.focalLength35mm?.let { add("Lens" to "%.0f mm (35 mm equiv.)".format(Locale.US, it)) }
        if (p.device.isNotBlank()) add("Device" to p.device)
    }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { (k, v) ->
            Row {
                Text(k, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, Modifier.weight(0.65f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FullScreenImage(vm: MapViewModel, p: PhotoDetail, onClose: () -> Unit) {
    var url by remember(p.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(p.id) { url = vm.imageUrl(p, "1600x0") }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _: Offset, zoom: Float, pan: Offset, _: Float ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = url,
                contentDescription = p.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transform)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (ReportReason, String) -> Unit) {
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report photo") },
        text = {
            Column {
                ReportReason.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == r, onClick = { reason = r }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(r.label, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 2000) note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = reason != null, onClick = { reason?.let { onSend(it, note.trim()) } }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDate(iso: String): String = runCatching {
    // PocketBase returns "2026-10-04 10:00:00.123Z"
    val instant = Instant.parse(iso.trim().replace(' ', 'T'))
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}.getOrDefault(iso)
