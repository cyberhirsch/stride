package app.stride

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.stride.store.SettingsState
import app.stride.ui.StrideRoot
import app.stride.ui.login.LoginScreen
import app.stride.ui.theme.StrideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StrideApp
        setContent {
            StrideTheme {
                val settings: SettingsState? by app.settings.state.collectAsState(initial = null)
                AppContent(settings)
            }
        }
    }
}

@Composable
private fun AppContent(settings: SettingsState?) {
    when {
        settings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        settings.session == null -> LoginScreen()
        else -> StrideRoot(settings)
    }
}
