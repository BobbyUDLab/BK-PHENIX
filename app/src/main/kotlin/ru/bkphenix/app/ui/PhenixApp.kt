package ru.bkphenix.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.bkphenix.app.R
import ru.bkphenix.app.permissions.Permissions
import ru.bkphenix.app.service.PhenixLocationService
import ru.bkphenix.app.ui.coordinate.CoordinateScreen
import ru.bkphenix.app.ui.diagnostics.DiagnosticsScreen
import ru.bkphenix.app.ui.satellites.SatellitesScreen

private enum class Destination(val route: String, val titleRes: Int, val icon: ImageVector) {
    COORDINATE("coordinate", R.string.screen_coordinate, Icons.Filled.LocationOn),
    SATELLITES("satellites", R.string.screen_satellites, Icons.Filled.Star),
    DIAGNOSTICS("diagnostics", R.string.screen_diagnostics, Icons.Filled.Build),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhenixApp() {
    val context = LocalContext.current
    var locationGranted by remember { mutableStateOf(Permissions.hasFineLocation(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGranted = Permissions.hasFineLocation(context)
        if (locationGranted) PhenixLocationService.start(context)
    }
    LaunchedEffect(Unit) {
        if (locationGranted) PhenixLocationService.start(context) else launcher.launch(Permissions.all.toTypedArray())
    }
    LifecycleResumeEffect(Unit) {
        locationGranted = Permissions.hasFineLocation(context)
        onPauseOrDispose { }
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = Destination.entries.firstOrNull { it.route == backStack?.destination?.route } ?: Destination.COORDINATE

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(current.titleRes)) }) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == current,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(stringResource(d.titleRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Destination.COORDINATE.route, modifier = Modifier.padding(padding)) {
            composable(Destination.COORDINATE.route) {
                CoordinateScreen(
                    viewModel = viewModel(factory = ViewModelFactory.instance),
                    locationGranted = locationGranted,
                    onRequestPermissions = { launcher.launch(Permissions.all.toTypedArray()) },
                )
            }
            composable(Destination.SATELLITES.route) { SatellitesScreen(viewModel(factory = ViewModelFactory.instance)) }
            composable(Destination.DIAGNOSTICS.route) { DiagnosticsScreen(viewModel(factory = ViewModelFactory.instance)) }
        }
    }
}
