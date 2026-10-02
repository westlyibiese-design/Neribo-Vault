package com.westly.neribovault.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Route names for Settings. */
object SettingsRoutes {
    const val LIST = Routes.SETTINGS
}

/** Registers the Settings screen. */
fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable(SettingsRoutes.LIST) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenSecurity = { navController.navigate(Routes.SECURITY) },
            onOpenBackup = { navController.navigate(Routes.BACKUP) },
            onOpenCloud = { navController.navigate(Routes.CLOUD) },
            onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
        )
    }
}
