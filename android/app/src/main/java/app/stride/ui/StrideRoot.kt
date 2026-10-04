package app.stride.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.stride.StrideApp
import app.stride.store.SettingsState
import app.stride.store.UploadStatus
import app.stride.ui.camera.CameraScreen
import app.stride.ui.map.MapScreen
import app.stride.ui.queue.QueueScreen
import app.stride.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Camera("camera", "Camera", Icons.Filled.CameraAlt),
    Queue("queue", "Uploads", Icons.Filled.CloudUpload),
    Map("map", "Map", Icons.Filled.Map),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun StrideRoot(settings: SettingsState) {
    val app = LocalContext.current.applicationContext as StrideApp
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val records by app.captures.records.collectAsState()
    val open = records.count { it.status != UploadStatus.UPLOADED }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (tab == Tab.Queue && open > 0) {
                                BadgedBox(badge = { Badge { Text("$open") } }) { Icon(tab.icon, tab.label) }
                            } else Icon(tab.icon, tab.label)
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Camera.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Camera.route) { CameraScreen() }
            composable(Tab.Queue.route) { QueueScreen() }
            composable(Tab.Map.route) { MapScreen(settings) }
            composable(Tab.Settings.route) { SettingsScreen(settings) }
        }
    }
}
