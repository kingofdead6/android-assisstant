package com.john.assistant.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.john.assistant.presentation.history.HistoryScreen
import com.john.assistant.presentation.home.HomeScreen
import com.john.assistant.presentation.models.ModelsScreen
import com.john.assistant.presentation.permissions.PermissionsScreen
import com.john.assistant.presentation.settings.SettingsScreen

/** Destinations. Flat by design — this is a five-screen app, not a hierarchy. */
object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val MODELS = "models"
}

/**
 * The navigation graph.
 *
 * Home is the start destination and everything else is a leaf off it, because
 * the assistant screen is where the user always wants to end up. Back from
 * anywhere returns to the orb.
 */
@Composable
fun JohnApp(
    startListeningImmediately: Boolean = false,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                startListeningImmediately = startListeningImmediately,
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onBack = navController::popBackStack)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = navController::popBackStack,
                onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
            )
        }

        composable(Routes.PERMISSIONS) {
            PermissionsScreen(onBack = navController::popBackStack)
        }

        composable(Routes.MODELS) {
            ModelsScreen(onBack = navController::popBackStack)
        }
    }
}
