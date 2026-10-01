package com.westly.neribovault.feature.developer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.lock.VaultLockGate
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.feature.developer.bugs.BugEditorScreen
import com.westly.neribovault.feature.developer.docs.ProjectDocEditorScreen
import com.westly.neribovault.feature.developer.docs.PromptEditorScreen
import com.westly.neribovault.feature.developer.docs.PromptsLibraryScreen
import com.westly.neribovault.feature.developer.home.DeveloperHomeScreen
import com.westly.neribovault.feature.developer.plans.FolderPlanEditorScreen
import com.westly.neribovault.feature.developer.plans.PlanningDocEditorScreen
import com.westly.neribovault.feature.developer.projects.ProjectDetailScreen
import com.westly.neribovault.feature.developer.projects.ProjectEditorScreen
import com.westly.neribovault.feature.developer.secrets.SecretActivityScreen
import com.westly.neribovault.feature.developer.secrets.SecretEditorScreen
import com.westly.neribovault.feature.developer.secrets.SecretsVault
import com.westly.neribovault.feature.developer.tasks.TaskEditorScreen
import com.westly.neribovault.feature.developer.tasks.TasksOverviewScreen

/** Route names, argument keys and route builders for the Developer vault. */
object DeveloperRoutes {
    const val LIST = Routes.DEVELOPER
    const val PROJECT_DETAIL = "developer/project/detail/{projectId}"
    const val PROJECT_EDIT = "developer/project/edit/{projectId}"            // "new" creates
    const val SECRET_EDITOR = "developer/secret/{projectId}/{secretId}"      // "new" creates
    const val SECRET_ACTIVITY = "developer/activity"
    const val BUG = "developer/bug/{projectId}/{bugId}"
    const val TASK = "developer/task/{projectId}/{taskId}"                   // projectId may be "none"
    const val TASKS = "developer/tasks"
    const val PLAN = "developer/plan/{projectId}/{docId}"
    const val FOLDER_PLAN = "developer/folderplan/{projectId}/{planId}"
    const val DOC = "developer/doc/{projectId}/{docId}"
    const val PROMPT = "developer/prompt/{projectId}/{promptId}"             // projectId may be "none"
    const val PROMPTS = "developer/prompts"
    const val TRASH = "developer/trash"

    const val ARG_PROJECT_ID = "projectId"
    const val ARG_SECRET_ID = "secretId"
    const val ARG_BUG_ID = "bugId"
    const val ARG_TASK_ID = "taskId"
    const val ARG_DOC_ID = "docId"
    const val ARG_PLAN_ID = "planId"
    const val ARG_PROMPT_ID = "promptId"

    /** The project id that means "no project" for items that may be unlinked. */
    const val NONE = "none"

    /** The item id that means "create a new item". */
    const val NEW = "new"

    /** Key a screen uses to hand a just-deleted id back to the screen before it. */
    const val KEY_DELETED_ID = "deletedId"

    fun projectDetail(id: String) = "developer/project/detail/$id"
    fun projectEdit(id: String) = "developer/project/edit/$id"
    fun secretEditor(projectId: String, secretId: String) = "developer/secret/$projectId/$secretId"
    fun bug(projectId: String, bugId: String) = "developer/bug/$projectId/$bugId"
    fun task(projectId: String?, taskId: String) = "developer/task/${projectId ?: NONE}/$taskId"
    fun plan(projectId: String, docId: String) = "developer/plan/$projectId/$docId"
    fun folderPlan(projectId: String, planId: String) = "developer/folderplan/$projectId/$planId"
    fun doc(projectId: String, docId: String) = "developer/doc/$projectId/$docId"
    fun prompt(projectId: String?, promptId: String) = "developer/prompt/${projectId ?: NONE}/$promptId"
}

private const val VAULT_ID = "developer"
private const val VAULT_NAME = "Developer"

/** Wraps a Developer screen in the vault lock and makes sure the secrets vault is watching. */
@Composable
private fun DeveloperGate(navController: NavHostController, content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        SecretsVault.attach(context)
        true
    }
    VaultLockGate(
        vaultId = VAULT_ID,
        vaultName = VAULT_NAME,
        onBack = { navController.popBackStack() },
        content = content,
    )
}

private fun NavBackStackEntry.arg(name: String, default: String): String =
    arguments?.getString(name) ?: default

/** Turns the route value "none" into null. */
private fun String.toProjectIdOrNull(): String? = if (this == DeveloperRoutes.NONE) null else this

private fun stringArgs(vararg names: String) =
    names.map { name -> navArgument(name) { type = NavType.StringType } }

/** Registers every Developer vault screen, each behind the vault lock. */
fun NavGraphBuilder.developerGraph(navController: NavHostController) {
    val back: () -> Unit = { navController.popBackStack() }

    composable(DeveloperRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(DeveloperRoutes.KEY_DELETED_ID, null)
        }
        DeveloperGate(navController) {
            DeveloperHomeScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    entry.savedStateHandle.set<String?>(DeveloperRoutes.KEY_DELETED_ID, null)
                },
                onBack = back,
                onOpenProject = { id ->
                    navController.navigate(DeveloperRoutes.projectDetail(id)) { launchSingleTop = true }
                },
                onEditProject = { id ->
                    navController.navigate(DeveloperRoutes.projectEdit(id)) { launchSingleTop = true }
                },
                onNewProject = {
                    navController.navigate(DeveloperRoutes.projectEdit(DeveloperRoutes.NEW)) {
                        launchSingleTop = true
                    }
                },
                onOpenTasks = {
                    navController.navigate(DeveloperRoutes.TASKS) { launchSingleTop = true }
                },
                onOpenPrompts = {
                    navController.navigate(DeveloperRoutes.PROMPTS) { launchSingleTop = true }
                },
                onOpenActivity = {
                    navController.navigate(DeveloperRoutes.SECRET_ACTIVITY) { launchSingleTop = true }
                },
                onOpenTrash = {
                    navController.navigate(DeveloperRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }

    composable(
        route = DeveloperRoutes.PROJECT_DETAIL,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        DeveloperGate(navController) {
            ProjectDetailScreen(
                projectId = projectId,
                onBack = back,
                onEdit = {
                    navController.navigate(DeveloperRoutes.projectEdit(projectId)) {
                        launchSingleTop = true
                    }
                },
                onDeleted = { deletedId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(DeveloperRoutes.KEY_DELETED_ID, deletedId)
                    navController.popBackStack()
                },
                onOpenSecret = { secretId ->
                    navController.navigate(DeveloperRoutes.secretEditor(projectId, secretId)) {
                        launchSingleTop = true
                    }
                },
                onOpenBug = { bugId ->
                    navController.navigate(DeveloperRoutes.bug(projectId, bugId)) { launchSingleTop = true }
                },
                onOpenTask = { taskId ->
                    navController.navigate(DeveloperRoutes.task(projectId, taskId)) { launchSingleTop = true }
                },
                onOpenPlanningDoc = { docId ->
                    navController.navigate(DeveloperRoutes.plan(projectId, docId)) { launchSingleTop = true }
                },
                onOpenFolderPlan = { planId ->
                    navController.navigate(DeveloperRoutes.folderPlan(projectId, planId)) {
                        launchSingleTop = true
                    }
                },
                onOpenDocument = { docId ->
                    navController.navigate(DeveloperRoutes.doc(projectId, docId)) { launchSingleTop = true }
                },
                onOpenPrompt = { promptId ->
                    navController.navigate(DeveloperRoutes.prompt(projectId, promptId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
    }

    composable(
        route = DeveloperRoutes.PROJECT_EDIT,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            ProjectEditorScreen(projectId = projectId, onBack = back, onSaved = back)
        }
    }

    composable(
        route = DeveloperRoutes.SECRET_EDITOR,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_SECRET_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val secretId = entry.arg(DeveloperRoutes.ARG_SECRET_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            SecretEditorScreen(
                projectId = projectId.toProjectIdOrNull(),
                secretId = secretId,
                onBack = back,
                onSaved = back,
            )
        }
    }

    composable(DeveloperRoutes.SECRET_ACTIVITY) {
        DeveloperGate(navController) { SecretActivityScreen(onBack = back) }
    }

    composable(
        route = DeveloperRoutes.BUG,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_BUG_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val bugId = entry.arg(DeveloperRoutes.ARG_BUG_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            BugEditorScreen(projectId = projectId, bugId = bugId, onBack = back)
        }
    }

    composable(
        route = DeveloperRoutes.TASK,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_TASK_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val taskId = entry.arg(DeveloperRoutes.ARG_TASK_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            TaskEditorScreen(projectId = projectId.toProjectIdOrNull(), taskId = taskId, onBack = back)
        }
    }

    composable(DeveloperRoutes.TASKS) {
        DeveloperGate(navController) {
            TasksOverviewScreen(
                onOpenTask = { projectId, taskId ->
                    navController.navigate(DeveloperRoutes.task(projectId, taskId)) {
                        launchSingleTop = true
                    }
                },
                onBack = back,
            )
        }
    }

    composable(
        route = DeveloperRoutes.PLAN,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_DOC_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val docId = entry.arg(DeveloperRoutes.ARG_DOC_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            PlanningDocEditorScreen(projectId = projectId, docId = docId, onBack = back)
        }
    }

    composable(
        route = DeveloperRoutes.FOLDER_PLAN,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_PLAN_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val planId = entry.arg(DeveloperRoutes.ARG_PLAN_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            FolderPlanEditorScreen(projectId = projectId, planId = planId, onBack = back)
        }
    }

    composable(
        route = DeveloperRoutes.DOC,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_DOC_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val docId = entry.arg(DeveloperRoutes.ARG_DOC_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            ProjectDocEditorScreen(projectId = projectId, docId = docId, onBack = back)
        }
    }

    composable(
        route = DeveloperRoutes.PROMPT,
        arguments = stringArgs(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.ARG_PROMPT_ID),
    ) { entry ->
        val projectId = entry.arg(DeveloperRoutes.ARG_PROJECT_ID, DeveloperRoutes.NONE)
        val promptId = entry.arg(DeveloperRoutes.ARG_PROMPT_ID, DeveloperRoutes.NEW)
        DeveloperGate(navController) {
            PromptEditorScreen(
                projectId = projectId.toProjectIdOrNull(),
                promptId = promptId,
                onBack = back,
            )
        }
    }

    composable(DeveloperRoutes.PROMPTS) {
        DeveloperGate(navController) {
            PromptsLibraryScreen(
                onOpenPrompt = { projectId, promptId ->
                    navController.navigate(DeveloperRoutes.prompt(projectId, promptId)) {
                        launchSingleTop = true
                    }
                },
                onBack = back,
            )
        }
    }

    composable(DeveloperRoutes.TRASH) {
        DeveloperGate(navController) { DeveloperTrashScreen(onBack = back) }
    }
}
