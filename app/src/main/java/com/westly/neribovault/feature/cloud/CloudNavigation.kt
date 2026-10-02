package com.westly.neribovault.feature.cloud

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Route names for Cloud sync. */
object CloudRoutes {
    const val MAIN = Routes.CLOUD
}

/** Registers the Cloud sync screen. */
fun NavGraphBuilder.cloudGraph(navController: NavHostController) {
    composable(CloudRoutes.MAIN) {
        CloudScreen(onBack = { navController.popBackStack() })
    }
}
