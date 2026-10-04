package app.stride.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.stride.store.License
import app.stride.sensors.LocationTracker
import app.stride.sensors.OrientationMath
import app.stride.ui.theme.Amber
import app.stride.ui.theme.WarnRed
import java.util.Locale

private val CAMERA_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var hasLocation by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        asked = true
        hasCamera = result[Manifest.permission.CAMERA] ?: hasCamera
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocation
    }
    LaunchedEffect(Unit) {
        if (!hasCamera || !hasLocation) launcher.launch(CAMERA_PERMISSIONS)
    }

    if (!hasCamera || !hasLocation) {
        PermissionRationale(
            missingCamera = !hasCamera,
            missingLocation = !hasLocation,
            asked = asked,
            onRequest = { launcher.launch(CAMERA_PERMISSIONS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
        // re-check when returning from the system settings
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    hasCamera = granted(Manifest.permission.CAMERA)
                    hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        return
    }

    CameraContent(vm)
}

@Composable
private fun CameraContent(vm: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var titleFor by remember { mutableStateOf<String?>(null) }

    // sensors run only while this screen is resumed
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.startSensors(locationGranted = true)
                Lifecycle.Event.ON_PAUSE -> vm.stopSensors()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            vm.stopSensors()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.unbindAll()
        val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, vm.imageCapture)
        vm.onCameraBound(camera)
    }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is CameraEvent.Message -> snackbar.showSnackbar(e.text)
                is CameraEvent.Saved -> {
                    val r = snackbar.showSnackbar("Saved, uploads when online", actionLabel = "Add title")
                    if (r == SnackbarResult.ActionPerformed) titleFor = e.id
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Hud(vm, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))
        Controls(vm, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    titleFor?.let { id ->
        TitleDialog(
            initial = "",
            onDismiss = { titleFor = null },
            onSave = { vm.setTitle(id, it); titleFor = null },
        )
    }
}

@Composable
private fun Hud(vm: CameraViewModel, modifier: Modifier) {
    val reading by vm.orientation.reading.collectAsStateWithLifecycle()
    val loc by vm.location.location.collectAsStateWithLifecycle()
    val declination = vm.declination(loc)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            val gps = loc?.let { l ->
                val age = LocationTracker.ageMillis(l) / 1000
                val acc = if (l.hasAccuracy()) "±%.1f m".format(Locale.US, l.accuracy) else "±? m"
                val color = if (!l.hasAccuracy() || l.accuracy > 15 || age > 10) Amber else Color.White
                "GPS $acc" + (if (age > 2) " · ${age}s old" else "") to color
            } ?: ("GPS waiting for fix…" to WarnRed)
            HudLine(gps.first, gps.second)

            val r = reading
            if (r == null) {
                HudLine(if (vm.orientation.available) "Compass starting…" else "No orientation sensor", Amber)
            } else {
                val heading = OrientationMath.normalizeDegrees(r.angles.azimuth + (declination ?: 0.0))
                val north = if (declination != null) "T" else "M"
                val acc = r.headingAccuracy?.let { " ±%.0f°".format(Locale.US, it) } ?: ""
                HudLine(
                    "%03.0f°%s %s%s".format(Locale.US, heading, north, OrientationMath.compassPoint(heading), acc),
                    if (r.needsCalibration) Amber else Color.White,
                    big = true,
                )
                HudLine("pitch %+5.1f°   roll %+5.1f°".format(Locale.US, r.angles.pitch, r.angles.roll), Color.White)
            }
        }
        if (reading?.needsCalibration == true) {
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WarnRed.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Compass inaccurate. Move the phone in a figure 8 a few times.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HudLine(text: String, color: Color, big: Boolean = false) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = if (big) 20.sp else 14.sp)
}

@Composable
private fun Controls(vm: CameraViewModel, modifier: Modifier) {
    var licenseMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = vm.title,
            onValueChange = { if (it.length <= 200) vm.title = it },
            placeholder = { Text("Title (optional)", color = Color.White.copy(alpha = 0.7f)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.9f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
            ),
        )
        Spacer(Modifier.size(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(Modifier.width(110.dp)) {
                AssistChip(
                    onClick = { licenseMenu = true },
                    label = { Text(vm.license.label, maxLines = 1) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        labelColor = Color.White,
                    ),
                )
                DropdownMenu(expanded = licenseMenu, onDismissRequest = { licenseMenu = false }) {
                    License.entries.forEach { l ->
                        DropdownMenuItem(text = { Text(l.label) }, onClick = { vm.chooseLicense(l); licenseMenu = false })
                    }
                }
            }
            Shutter(onClick = vm::shoot)
            Box(Modifier.width(110.dp), contentAlignment = Alignment.Center) {
                if (vm.processing > 0) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun Shutter(onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
    )
}

@Composable
fun TitleDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Title") },
        text = {
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 200) text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PermissionRationale(
    missingCamera: Boolean,
    missingLocation: Boolean,
    asked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Stride needs", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(12.dp))
        if (missingCamera) Text("• the camera, to take photos")
        if (missingLocation) Text("• precise location, to place each photo on the map")
        Spacer(Modifier.size(24.dp))
        Button(onClick = onRequest) { Text("Grant access") }
        if (asked) TextButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}
