package com.brainplaner.phone.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Session : Screen("session")
    object Reflection : Screen("reflection/{sessionId}") {
        fun route(sessionId: String) = "reflection/$sessionId"
    }
}
