package com.flareaward.serendip.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flareaward.serendip.R
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.components.LoadingState
import com.flareaward.serendip.presentation.dashboard.DashboardScreen
import com.flareaward.serendip.presentation.history.HistoryScreen
import com.flareaward.serendip.presentation.onboarding.OnboardingScreen
import com.flareaward.serendip.presentation.photos.PhotoViewerScreen
import com.flareaward.serendip.presentation.photos.PhotosScreen
import com.flareaward.serendip.presentation.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val PHOTOS = "photos"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val PHOTO = "photo/{eventId}"

    fun photo(eventId: Long) = "photo/$eventId"
}

private enum class Tab(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    DASHBOARD(Routes.DASHBOARD, R.string.tab_dashboard, Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    PHOTOS(Routes.PHOTOS, R.string.tab_photos, Icons.Outlined.PhotoLibrary, Icons.Rounded.PhotoLibrary),
    HISTORY(Routes.HISTORY, R.string.tab_history, Icons.Outlined.History, Icons.Rounded.History),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/**
 * Single navigation graph: onboarding → four tabs → full-screen photo viewer.
 *
 * @param pendingPhotoEventId event id delivered by a tapped "photo saved"
 *   notification; consumed (via [onPendingPhotoConsumed]) as soon as it is shown.
 */
@Composable
fun SerendipNavHost(
    pendingPhotoEventId: StateFlow<Long?>,
    onPendingPhotoConsumed: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val onboardingCompleted by graph.appFlags.onboardingCompleted.collectAsStateWithLifecycle(initialValue = null)
    val completed = onboardingCompleted
    if (completed == null) {
        // DataStore has not answered yet (a few milliseconds); avoid flashing the wrong start screen.
        LoadingState()
        return
    }

    // Fixed for the lifetime of this composition so the graph is never rebuilt; after
    // onboarding we simply navigate to the dashboard and drop the onboarding entry.
    val startDestination = rememberSaveable { if (completed) Routes.DASHBOARD else Routes.ONBOARDING }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = Tab.entries.any { tab -> currentDestination?.hierarchy?.any { it.route == tab.route } == true }

    val pendingPhoto by pendingPhotoEventId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPhoto, completed) {
        val id = pendingPhoto ?: return@LaunchedEffect
        if (completed) {
            navController.navigate(Routes.photo(id)) { launchSingleTop = true }
            onPendingPhotoConsumed()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Routes.DASHBOARD) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTab(tab.route) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(150)) },
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        snackbarHostState = snackbarHostState,
                        onOpenPhoto = { id -> navController.navigate(Routes.photo(id)) },
                        onOpenSettings = { navigateToTab(Routes.SETTINGS) },
                    )
                }
                composable(Routes.PHOTOS) {
                    PhotosScreen(
                        onOpenPhoto = { id -> navController.navigate(Routes.photo(id)) },
                        onOpenDashboard = { navigateToTab(Routes.DASHBOARD) },
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(onOpenPhoto = { id -> navController.navigate(Routes.photo(id)) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(snackbarHostState = snackbarHostState)
                }
                composable(
                    route = Routes.PHOTO,
                    arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                ) { entry ->
                    val eventId = entry.arguments?.getLong("eventId") ?: return@composable
                    PhotoViewerScreen(eventId = eventId, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
