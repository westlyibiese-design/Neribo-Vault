package com.westly.neribovault.feature.diary

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Diary vault. */
object DiaryRoutes {
    const val LIST = Routes.DIARY
}

/** Registers the Diary vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.diaryGraph(navController: NavHostController) {
    composable(DiaryRoutes.LIST) {
        PlaceholderScreen(
            title = "Diary",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
