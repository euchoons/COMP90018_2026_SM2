package au.edu.unimelb.floraguide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.unimelb.floraguide.domain.model.AppScreen
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.ui.screens.AuthScreen
import au.edu.unimelb.floraguide.ui.screens.CollectionScreen
import au.edu.unimelb.floraguide.ui.screens.HomeScreen
import au.edu.unimelb.floraguide.ui.screens.ResultsScreen
import au.edu.unimelb.floraguide.ui.screens.ScanScreen

@Composable
fun FloraGuideApp(viewModel: FloraGuideViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    if (authState !is AuthState.Authenticated && authState != AuthState.OfflineGuest) {
        Surface {
            AuthScreen(
                authState = authState,
                onSignIn = viewModel::signIn,
                onRegister = viewModel::register,
                onAnonymousSignIn = viewModel::signInAnonymously,
                onContinueOffline = viewModel::continueOffline,
                onImportLocal = viewModel::importLocalObservations,
                onRetrySync = viewModel::retrySync,
                onSignOut = viewModel::signOut,
                modifier = Modifier.safeDrawingPadding(),
            )
        }
        return
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = state.screen != AppScreen.HOME) {
        when (state.screen) {
            AppScreen.RESULTS -> viewModel.goToScan()
            AppScreen.SCAN, AppScreen.COLLECTION, AppScreen.ACCOUNT -> viewModel.goHome()
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
                    onAccount = viewModel::goToAccount,
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
                onCaptureStarted = viewModel::beginCapture,
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
                onRetryIdentification = viewModel::retryIdentification,
                onConfirm = viewModel::confirmSelectedObservation,
                modifier = Modifier.padding(padding),
            )

            AppScreen.COLLECTION -> CollectionScreen(
                state = state,
                onStartScan = viewModel::goToScan,
                onDelete = viewModel::deleteObservation,
                modifier = Modifier.padding(padding),
            )

            AppScreen.ACCOUNT -> AuthScreen(
                authState = authState,
                onSignIn = viewModel::signIn,
                onRegister = viewModel::register,
                onAnonymousSignIn = viewModel::signInAnonymously,
                onContinueOffline = viewModel::continueOffline,
                onImportLocal = viewModel::importLocalObservations,
                onRetrySync = viewModel::retrySync,
                onSignOut = viewModel::signOut,
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
    onAccount: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        FloraGuideNavigationItem(selected == AppScreen.HOME, onHome, Icons.Default.Home, "Home")
        FloraGuideNavigationItem(selected == AppScreen.SCAN, onScan, Icons.Default.CameraAlt, "Observe")
        FloraGuideNavigationItem(selected == AppScreen.COLLECTION, onCollection, Icons.Default.CollectionsBookmark, "Field guide")
        FloraGuideNavigationItem(selected == AppScreen.ACCOUNT, onAccount, Icons.Default.Person, "Account")
    }
}

@Composable
private fun RowScope.FloraGuideNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = {
            Text(
                text = navigationLabel(label, LocalDensity.current.fontScale),
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) },
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            unselectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

internal fun navigationLabel(label: String, fontScale: Float): String = if (fontScale < 1.5f) label else when (label) {
    "Observe" -> "Scan"
    "Field guide" -> "Guide"
    "Account" -> "Me"
    else -> label
}
