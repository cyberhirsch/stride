package app.stride.ui.login

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.stride.StrideApp
import app.stride.ui.settings.ServerUrlDialog
import kotlinx.coroutines.launch

class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StrideApp

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submit(register: Boolean, email: String, password: String, name: String) {
        if (busy) return
        error = null
        busy = true
        viewModelScope.launch {
            try {
                if (register) app.api.register(email.trim(), password, name.trim())
                app.settings.saveSession(app.api.login(email.trim(), password))
                app.requeueUploads()
            } catch (e: Exception) {
                error = e.message ?: "Could not reach the server"
            } finally {
                busy = false
            }
        }
    }

    fun setServer(url: String) = viewModelScope.launch { app.settings.setServerUrl(url) }
}

@Composable
fun LoginScreen(vm: LoginViewModel = viewModel()) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as StrideApp
    val serverUrl = app.settings.state.collectAsState(initial = null).value?.serverUrl.orEmpty()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var editServer by remember { mutableStateOf(false) }

    val valid = email.contains('@') && password.length >= 8 && (!register || name.isNotBlank())

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Stride", style = MaterialTheme.typography.displayMedium)
            Text(
                "Photos with position, direction and tilt, on a shared map.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !register, onClick = { register = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Log in") }
                SegmentedButton(
                    selected = register, onClick = { register = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Register") }
            }
            if (register) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(if (register) "Password (min. 8 characters)" else "Password") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.submit(register, email, password, name) },
                enabled = valid && !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                else Text(if (register) "Create account" else "Log in")
            }
            Spacer(Modifier.height(24.dp))
            Text("Server: $serverUrl", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { editServer = true }) { Text("Change server") }
        }
    }

    if (editServer) {
        ServerUrlDialog(
            current = serverUrl,
            onDismiss = { editServer = false },
            onSave = { vm.setServer(it); editServer = false },
        )
    }
}
