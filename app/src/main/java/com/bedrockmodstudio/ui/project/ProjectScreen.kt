package com.bedrockmodstudio.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProjectScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val meta by produceState<Result<ProjectMeta>?>(null, id) {
        value = withContext(Dispatchers.IO) { runCatching { app.store.load(id) } }
    }
    val m = meta
    if (m == null) { LoadingBox(); return }
    val project = m.getOrElse {
        Scaffold(topBar = { BmsTopBar(stringResource(R.string.app_name), onBack = { nav.popBackStack() }) }) { p ->
            Column(Modifier.padding(p).padding(16.dp)) { InfoCard(it.message ?: "Erreur", color = BmsColors.Error) }
        }
        return
    }

    Scaffold(
        topBar = {
            BmsTopBar(
                project.name,
                subtitle = stringResource(R.string.project_namespace_version, project.namespace, project.targetVersion, project.packVersion.toString()),
                onBack = { nav.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (project.imported) InfoCard(stringResource(R.string.project_imported_note))
            val tile = Modifier.fillMaxWidth()
            ActionTile(Icons.Filled.Pets, stringResource(R.string.action_mob), stringResource(R.string.action_mob_desc), tile, BmsColors.Secondary) {
                nav.navigate(Routes.mob(id))
            }
            ActionTile(Icons.Filled.Hardware, stringResource(R.string.action_item), stringResource(R.string.action_item_desc), tile, BmsColors.Secondary) {
                nav.navigate(Routes.item(id))
            }
            ActionTile(Icons.Filled.ViewInAr, stringResource(R.string.action_block), stringResource(R.string.action_block_desc), tile, BmsColors.Secondary) {
                nav.navigate(Routes.block(id))
            }
            ActionTile(Icons.Filled.GridOn, stringResource(R.string.action_recipe), stringResource(R.string.action_recipe_desc), tile, BmsColors.Secondary) {
                nav.navigate(Routes.recipe(id))
            }
            ActionTile(Icons.Filled.Extension, stringResource(R.string.action_logic), stringResource(R.string.action_logic_desc), tile, BmsColors.Secondary) {
                nav.navigate(Routes.logic(id))
            }
            ActionTile(Icons.Filled.Public, stringResource(R.string.action_dimension), stringResource(R.string.action_dimension_desc), tile, BmsColors.Info) {
                nav.navigate(Routes.dimension(id))
            }
            ActionTile(Icons.Filled.Folder, stringResource(R.string.action_files), stringResource(R.string.action_files_desc), tile) {
                nav.navigate(Routes.files(id))
            }
            ActionTile(Icons.Filled.CheckCircle, stringResource(R.string.action_validate), stringResource(R.string.action_validate_desc), tile, BmsColors.Info) {
                nav.navigate(Routes.validate(id))
            }
            ActionTile(Icons.Filled.IosShare, stringResource(R.string.action_export), stringResource(R.string.action_export_desc), tile, BmsColors.Primary) {
                nav.navigate(Routes.export(id))
            }
            ActionTile(Icons.Filled.Search, stringResource(R.string.action_search), stringResource(R.string.action_search_desc), tile, BmsColors.Info) {
                nav.navigate(Routes.search(id))
            }
            ActionTile(Icons.Filled.Tune, stringResource(R.string.action_project_settings), stringResource(R.string.action_project_settings_desc), tile, BmsColors.TextMuted) {
                nav.navigate(Routes.projectSettings(id))
            }
            ActionTile(Icons.Filled.Delete, stringResource(R.string.action_trash), stringResource(R.string.action_trash_desc), tile, BmsColors.TextMuted) {
                nav.navigate(Routes.trash(id))
            }
        }
    }
}
