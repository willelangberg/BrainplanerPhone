package com.brainplaner.phone.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.brainplaner.phone.UiState
import com.brainplaner.phone.ui.home.HomeScreen
import com.brainplaner.phone.ui.home.HomeViewModel
import com.brainplaner.phone.ui.reflection.ReflectionScreen
import com.brainplaner.phone.ui.reflection.ReflectionViewModel
import com.brainplaner.phone.ui.session.SessionControllerScreen

@Composable
fun AppNavigation(
    userId: String,
    apiUrl: String,
    userToken: String,
    supabaseUrl: String,
    getActiveSessionId: () -> String?,
    onStartSession: suspend (plannedMinutes: Int) -> Result<String>,
    onStopSession: suspend () -> Result<String>,
    onLogout: () -> Unit,
    onStateChanged: ((UiState) -> Unit) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    var sessionPlannedMinutes by remember { mutableIntStateOf(60) }

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(userId, apiUrl, userToken, supabaseUrl)
            )
            HomeScreen(
                viewModel = vm,
                onStartSession = { minutes ->
                    sessionPlannedMinutes = minutes
                    navController.navigate(Screen.Session.route)
                },
            )
        }
        composable(Screen.Session.route) {
            SessionControllerScreen(
                onStart = { onStartSession(sessionPlannedMinutes) },
                onStop = {
                    // Capture session ID before stopping (MainActivity clears it after stop)
                    val sessionId = getActiveSessionId()
                    val result = onStopSession()
                    if (result.isSuccess && sessionId != null) {
                        navController.navigate(Screen.Reflection.route(sessionId)) {
                            popUpTo(Screen.Session.route) { inclusive = true }
                        }
                    }
                    result
                },
                onLogout = onLogout,
                userId = userId,
                onStateChanged = onStateChanged,
            )
        }
        composable(
            route = Screen.Reflection.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val vm: ReflectionViewModel = viewModel(
                factory = ReflectionViewModel.factory(sessionId, userId, apiUrl, userToken)
            )
            ReflectionScreen(
                viewModel = vm,
                onDone = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
            )
        }
    }
}


