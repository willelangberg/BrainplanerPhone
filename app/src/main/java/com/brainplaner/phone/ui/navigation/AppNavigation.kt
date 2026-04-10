package com.brainplaner.phone.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.brainplaner.phone.LocalStore
import com.brainplaner.phone.ui.budget.BudgetDetailScreen
import com.brainplaner.phone.ui.home.DailyCheckInScreen
import com.brainplaner.phone.ui.home.HomeScreen
import com.brainplaner.phone.ui.home.HomeViewModel
import com.brainplaner.phone.ui.reflection.ReflectionScreen
import com.brainplaner.phone.ui.reflection.ReflectionViewModel
import com.brainplaner.phone.ui.settings.SettingsScreen
import com.brainplaner.phone.ui.warmup.CognitiveWarmupScreen

private data class NavItem(val label: String, val route: String, val icon: @Composable () -> Unit)

@Composable
fun AppNavigation(
    userId: String,
    apiUrl: String,
    userToken: String,
    getActiveSessionId: () -> String?,
    onStartSession: suspend (plannedMinutes: Int) -> Result<String>,
    onStopSession: suspend () -> Result<String>,
    onPauseSession: suspend () -> Result<String>,
    onResumeSession: suspend () -> Result<String>,
    onLogout: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val application = LocalContext.current.applicationContext as Application

    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.appFactory(application, userId, apiUrl, userToken)
    )
    val pendingReflectionSessionId = LocalStore.getPendingReflectionRouteSessionId(application)
    val startDestination = pendingReflectionSessionId?.let { Screen.Reflection.route(it) }
        ?: Screen.CognitiveWarmup.route

    val navItems = listOf(
        NavItem("Home", Screen.Home.route) { Icon(Icons.Default.Home, contentDescription = "Home") },
        NavItem("Settings", Screen.Settings.route) { Icon(Icons.Default.Settings, contentDescription = "Settings") },
    )

    // Screens that show the bottom bar
    val navBarRoutes = setOf(Screen.Home.route, Screen.Settings.route)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in navBarRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    navItems.forEach { item ->
                        val selected = navBackStackEntry?.destination?.hierarchy?.any {
                            it.route == item.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = item.icon,
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = if (showBottomBar) Modifier.padding(innerPadding) else Modifier,
        ) {
            composable(Screen.CognitiveWarmup.route) {
                if (!LocalStore.isWarmupEnabled(application) || LocalStore.getTodayWarmupData(application) != null) {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.DailyCheckIn.route) {
                            popUpTo(Screen.CognitiveWarmup.route) { inclusive = true }
                        }
                    }
                    return@composable
                }
                CognitiveWarmupScreen(
                    baselineMs = LocalStore.getWarmupBaseline(application),
                    onComplete = { medianMs ->
                        LocalStore.saveWarmupResult(application, medianMs)
                        homeViewModel.refreshCloudData()
                        navController.navigate(Screen.DailyCheckIn.route) {
                            popUpTo(Screen.CognitiveWarmup.route) { inclusive = true }
                        }
                    },
                    onSkip = {
                        navController.navigate(Screen.DailyCheckIn.route) {
                            popUpTo(Screen.CognitiveWarmup.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.DailyCheckIn.route) {
                DailyCheckInScreen(
                    viewModel = homeViewModel,
                    onContinue = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.DailyCheckIn.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = homeViewModel,
                    onStartSession = onStartSession,
                    onStopSession = {
                        val sessionId = getActiveSessionId()
                        val result = onStopSession()
                        if (result.isSuccess && sessionId != null) {
                            LocalStore.savePendingReflectionRoute(application, sessionId)
                            LocalStore.savePendingReflectionStage(application, LocalStore.REFLECTION_STAGE_FORM)
                            navController.navigate(Screen.Reflection.route(sessionId)) {
                                popUpTo(Screen.Home.route)
                            }
                        }
                        result
                    },
                    onPauseSession = onPauseSession,
                    onResumeSession = onResumeSession,
                    onBudgetDetail = {
                        navController.navigate(Screen.BudgetDetail.route)
                    },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onResetCheckIn = {
                        navController.navigate(Screen.DailyCheckIn.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onLogout = onLogout,
                )
            }
            composable(Screen.BudgetDetail.route) {
                BudgetDetailScreen(
                    viewModel = homeViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Screen.Reflection.route,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                val vm: ReflectionViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ReflectionViewModel(application, sessionId, userId, apiUrl, userToken) as T
                        }
                    }
                )
                ReflectionScreen(
                    viewModel = vm,
                    onDone = {
                        LocalStore.clearPendingReflectionRoute(application)
                        homeViewModel.refreshCloudData()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
