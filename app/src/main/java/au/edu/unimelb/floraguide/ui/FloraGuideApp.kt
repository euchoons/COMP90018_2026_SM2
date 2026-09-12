package au.edu.unimelb.floraguide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.unimelb.floraguide.domain.model.AppScreen
import au.edu.unimelb.floraguide.ui.screens.CollectionScreen
import au.edu.unimelb.floraguide.ui.screens.HomeScreen
import au.edu.unimelb.floraguide.ui.screens.ResultsScreen
import au.edu.unimelb.floraguide.ui.screens.ScanScreen

@Composable
fun FloraGuideApp(viewModel: FloraGuideViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = state.screen != AppScreen.HOME) {
        when (state.screen) {
            AppScreen.RESULTS -> viewModel.goToScan()
            AppScreen.SCAN, AppScreen.COLLECTION -> viewModel.goHome()
            AppScreen.HOME -> Unit
        }
    }

    val showNavigation = state.screen != AppScreen.RESULTS
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showNavigation) {
                FloraGuideNavigationBar(
                    selected = state.screen,
                    onHome = viewModel::goHome,
                    onScan = viewModel::goToScan,
                    onCollection = viewModel::goToCollection,
                )
            }
        },
    ) { padding ->
        when (state.screen) {
            AppScreen.HOME -> HomeScreen(
                state = state,
                onStartScan = viewModel::goToScan,
                onGuidedDemo = viewModel::runGuidedDemo,
                onOpenCollection = viewModel::goToCollection,
                modifier = Modifier.padding(padding),
            )

            AppScreen.SCAN -> ScanScreen(
                state = state,
                onHabitatSelected = viewModel::setHabitat,
                onPermissionResult = viewModel::onLocationPermissionResult,
                onUseDemoLocation = { viewModel.useCampusDemoLocation() },
                onPhotoCaptured = viewModel::analyzeCapturedPhoto,
                onGuidedDemo = viewModel::runGuidedDemo,
                onError = viewModel::showMessage,
                modifier = Modifier.padding(padding),
            )

            AppScreen.RESULTS -> ResultsScreen(
                state = state,
                onBackToScan = viewModel::goToScan,
                onHabitatSelected = viewModel::setHabitat,
                onSelectSpecies = viewModel::selectSpecies,
                onRetryContext = viewModel::retryContextLookup,
                onConfirm = viewModel::confirmSelectedObservation,
                modifier = Modifier.padding(padding),
            )

            AppScreen.COLLECTION -> CollectionScreen(
                state = state,
                onStartScan = viewModel::goToScan,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun FloraGuideNavigationBar(
    selected: AppScreen,
    onHome: () -> Unit,
    onScan: () -> Unit,
    onCollection: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == AppScreen.HOME,
            onClick = onHome,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = selected == AppScreen.SCAN,
            onClick = onScan,
            icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
            label = { Text("Observe") },
        )
        NavigationBarItem(
            selected = selected == AppScreen.COLLECTION,
            onClick = onCollection,
            icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
            label = { Text("Field guide") },
        )
    }
}
