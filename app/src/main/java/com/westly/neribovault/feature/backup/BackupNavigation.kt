package com.westly.neribovault.feature.backup

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Route names for Backup and restore. */
object BackupRoutes {
    const val MAIN = Routes.BACKUP
}

/** Registers the Backup and restore screen. */
fun NavGraphBuilder.backupGraph(navController: NavHostController) {
    composable(BackupRoutes.MAIN) {
        BackupScreen(onBack = { navController.popBackStack() })
    }
}
