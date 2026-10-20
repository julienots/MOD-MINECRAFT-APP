package com.bedrockmodstudio.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.storage.ProjectSummary
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.home.ProjectCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectsScreen(nav: NavHostController) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    val projects by produceState<List<ProjectSummary>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { app.store.list() }
    }

    Scaffold(
        topBar = { BmsTopBar(stringResource(R.string.projects_title)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.NEW_PROJECT) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.home_create_mod)) },
            )
        },
    ) { padding ->
        val list = projects
        if (list == null) { LoadingBox(); return@Scaffold }
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.projects_search)) }, singleLine = true,
            )
            val filtered = list.filter { query.isBlank() || it.meta.name.contains(query, true) || it.meta.namespace.contains(query, true) }
            if (filtered.isEmpty()) EmptyState(stringResource(R.string.home_no_projects))
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.meta.id }) { p ->
                    ProjectCard(p, trailing = {
                        IconButton(onClick = { toDelete = p }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
                    }) { nav.navigate(Routes.project(p.meta.id)) }
                }
            }
        }
    }

    toDelete?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.project_delete_title),
            body = stringResource(R.string.project_delete_body, p.meta.name),
            confirmLabel = stringResource(R.string.delete), destructive = true,
            onConfirm = {
                toDelete = null
                scope.launch(Dispatchers.Main.immediate) {
                    val r = withContext(Dispatchers.IO) { runCatching { app.store.moveToTrash(p.meta.id) } }
                    r.exceptionOrNull()?.let { snackbar.showSnackbar(it.message ?: "Erreur") }
                    refresh++
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}
