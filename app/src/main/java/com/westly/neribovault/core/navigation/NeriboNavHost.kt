package com.westly.neribovault.core.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.westly.neribovault.feature.church.churchGraph
import com.westly.neribovault.feature.developer.developerGraph
import com.westly.neribovault.feature.diagnostics.diagnosticsGraph
import com.westly.neribovault.feature.diary.diaryGraph
import com.westly.neribovault.feature.documents.documentsGraph
import com.westly.neribovault.feature.goals.goalsGraph
import com.westly.neribovault.feature.home.HomeScreen
import com.westly.neribovault.feature.ideas.ideasGraph
import com.westly.neribovault.feature.memories.memoriesGraph
import com.westly.neribovault.feature.notes.notesGraph
import com.westly.neribovault.feature.posts.postsGraph
import com.westly.neribovault.feature.security.securityGraph
import com.westly.neribovault.feature.settings.settingsGraph
import com.westly.neribovault.feature.writers.writersGraph

private const val ENTER_MS = 220
private const val EXIT_MS = 200

/**
 * The app's navigation host. Transitions (fade plus a 12dp horizontal slide) are set once here;
 * screens must not add their own.
 */
@Composable
fun NeriboNavHost() {
    val navController = rememberNavController()
    val slidePx = with(LocalDensity.current) { 12.dp.roundToPx() }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            fadeIn(animationSpec = tween(ENTER_MS)) +
                slideInHorizontally(animationSpec = tween(ENTER_MS)) { slidePx }
        },
        exitTransition = {
            fadeOut(animationSpec = tween(EXIT_MS))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(ENTER_MS)) +
                slideInHorizontally(animationSpec = tween(ENTER_MS)) { -slidePx }
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(EXIT_MS)) +
                slideOutHorizontally(animationSpec = tween(EXIT_MS)) { slidePx }
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenVault = { route -> navController.navigate(route) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        notesGraph(navController)
        ideasGraph(navController)
        goalsGraph(navController)
        diaryGraph(navController)
        writersGraph(navController)
        postsGraph(navController)
        churchGraph(navController)
        memoriesGraph(navController)
        documentsGraph(navController)
        developerGraph(navController)
        settingsGraph(navController)
        securityGraph(navController)
        diagnosticsGraph(navController)
    }
}
