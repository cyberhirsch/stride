package app.stride.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.stride.BuildConfig
import app.stride.StrideApp
import app.stride.store.License
import app.stride.store.SettingsRepository
import app.stride.store.SettingsState
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: SettingsState) {
    val app = LocalContext.current.applicationContext as StrideApp
    val scope = rememberCoroutineScope()
    var editServer by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Account")
            ListItem(
                headlineContent = { Text(settings.session?.name?.ifBlank { null } ?: "Signed in") },
                supportingContent = { Text(settings.session?.email.orEmpty()) },
                trailingContent = { OutlinedButton(onClick = { confirmLogout = true }) { Text("Log out") } },
            )
            HorizontalDivider()

            Section("Server")
            ListItem(
                headlineContent = { Text(settings.serverUrl) },
                supportingContent = {
                    Text(if (settings.serverUrl == SettingsRepository.DEFAULT_SERVER_URL) "Default of this build" else "Custom")
                },
                modifier = Modifier.clickable { editServer = true },
            )
            HorizontalDivider()

            Section("Default licence for new photos")
            License.entries.forEach { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.defaultLicense == l, onClick = {
                            scope.launch { app.settings.setDefaultLicense(l) }
                        })
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.defaultLicense == l, onClick = null)
                    Text(l.label, Modifier.padding(start = 12.dp))
                }
            }
            HorizontalDivider()
            Text(
                "Stride ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (editServer) {
        ServerUrlDialog(
            current = settings.serverUrl,
            onDismiss = { editServer = false },
            onSave = {
                editServer = false
                scope.launch { app.settings.setServerUrl(it) }
            },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("Photos that are not uploaded yet stay on the phone and upload after you log in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch { app.settings.clearSession() }
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun ServerUrlDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val url = text.trim().toHttpUrlOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server URL") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = url == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(
                    "Plain http is only allowed for the LAN dev server and localhost.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { text = SettingsRepository.DEFAULT_SERVER_URL }) { Text("Reset to default") }
            }
        },
        confirmButton = { TextButton(enabled = url != null, onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
