package com.bedrockmodstudio.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bedrockmodstudio.R
import com.bedrockmodstudio.ui.content.BlockWizardScreen
import com.bedrockmodstudio.ui.content.DimensionWizardScreen
import com.bedrockmodstudio.ui.content.ItemWizardScreen
import com.bedrockmodstudio.ui.content.RecipeWizardScreen
import com.bedrockmodstudio.ui.editor.EditorScreen
import com.bedrockmodstudio.ui.export.ExportScreen
import com.bedrockmodstudio.ui.files.FileExplorerScreen
import com.bedrockmodstudio.ui.files.HistoryScreen
import com.bedrockmodstudio.ui.files.ProjectTrashScreen
import com.bedrockmodstudio.ui.files.SearchScreen
import com.bedrockmodstudio.ui.home.HomeScreen
import com.bedrockmodstudio.ui.importing.ImportReportScreen
import com.bedrockmodstudio.ui.mob.MobWizardScreen
import com.bedrockmodstudio.ui.project.NewProjectScreen
import com.bedrockmodstudio.ui.project.ProjectScreen
import com.bedrockmodstudio.ui.project.ProjectSettingsScreen
import com.bedrockmodstudio.ui.projects.ProjectsScreen
import com.bedrockmodstudio.ui.settings.SettingsScreen
import com.bedrockmodstudio.ui.texture.TextureEditorScreen
import com.bedrockmodstudio.ui.validation.ValidationScreen

/** Routes de navigation. Les chemins de fichiers sont encodés pour pouvoir passer dans l'URL de route. */
object Routes {
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val NEW_PROJECT = "new_project"
    const val IMPORT_REPORT = "import_report"

    fun project(id: String) = "project/$id"
    fun files(id: String, dir: String = "") = "project/$id/files?dir=${Uri.encode(dir)}"
    fun edit(id: String, path: String, line: Int = 0) = "project/$id/edit?path=${Uri.encode(path)}&line=$line"
    fun history(id: String, path: String) = "project/$id/history?path=${Uri.encode(path)}"
    fun trash(id: String) = "project/$id/trash"
    fun search(id: String) = "project/$id/search"
    fun validate(id: String) = "project/$id/validate"
    fun export(id: String) = "project/$id/export"
    fun projectSettings(id: String) = "project/$id/settings"
    fun mob(id: String) = "project/$id/mob"
    fun item(id: String) = "project/$id/item"
    fun texture(id: String, path: String) = "project/$id/texture?path=${Uri.encode(path)}"
    fun block(id: String) = "project/$id/block"
    fun recipe(id: String) = "project/$id/recipe"
    fun dimension(id: String) = "project/$id/dimension"
}

/** Messages courts (« Enregistré. ») accessibles depuis tous les écrans. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

private data class TopLevel(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    TopLevel(Routes.PROJECTS, R.string.nav_projects, Icons.Filled.Folder),
    TopLevel(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (topLevel.any { it.route == current }) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        topLevel.forEach { item ->
                            NavigationBarItem(
                                selected = current == item.route,
                                onClick = { nav.navigateTopLevel(item.route) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.PROJECTS) { ProjectsScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.NEW_PROJECT) { NewProjectScreen(nav) }
                composable(Routes.IMPORT_REPORT) { ImportReportScreen(nav) }
                composable("project/{id}") { ProjectScreen(nav, it.arg("id")) }
                composable(
                    "project/{id}/files?dir={dir}",
                    arguments = listOf(navArgument("dir") { type = NavType.StringType; defaultValue = "" }),
                ) { FileExplorerScreen(nav, it.arg("id"), it.arg("dir")) }
                composable(
                    "project/{id}/edit?path={path}&line={line}",
                    arguments = listOf(
                        navArgument("path") { type = NavType.StringType; defaultValue = "" },
                        navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                    ),
                ) { EditorScreen(nav, it.arg("id"), it.arg("path"), it.arguments?.getInt("line") ?: 0) }
                composable(
                    "project/{id}/history?path={path}",
                    arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                ) { HistoryScreen(nav, it.arg("id"), it.arg("path")) }
                composable("project/{id}/trash") { ProjectTrashScreen(nav, it.arg("id")) }
                composable("project/{id}/search") { SearchScreen(nav, it.arg("id")) }
                composable("project/{id}/validate") { ValidationScreen(nav, it.arg("id")) }
                composable("project/{id}/export") { ExportScreen(nav, it.arg("id")) }
                composable("project/{id}/settings") { ProjectSettingsScreen(nav, it.arg("id")) }
                composable("project/{id}/mob") { MobWizardScreen(nav, it.arg("id")) }
                composable(
                    "project/{id}/texture?path={path}",
                    arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                ) { TextureEditorScreen(nav, it.arg("id"), it.arg("path")) }
                composable("project/{id}/item") { ItemWizardScreen(nav, it.arg("id")) }
                composable("project/{id}/block") { BlockWizardScreen(nav, it.arg("id")) }
                composable("project/{id}/recipe") { RecipeWizardScreen(nav, it.arg("id")) }
                composable("project/{id}/dimension") { DimensionWizardScreen(nav, it.arg("id")) }
            }
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(name: String): String = arguments?.getString(name) ?: ""

fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
