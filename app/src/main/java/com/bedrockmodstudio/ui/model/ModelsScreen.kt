package com.bedrockmodstudio.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model3d.ModelBinding
import com.bedrockmodstudio.core.model3d.ModelTemplate
import com.bedrockmodstudio.core.model3d.ModelUse
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.ColorRow
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var use by remember { mutableStateOf(ModelUse.CREATURE) }
    var template by remember { mutableStateOf(ModelTemplate.HUMANOID) }
    var main by remember { mutableIntStateOf(0xFF2E6FA8.toInt()) }
    var accent by remember { mutableIntStateOf(0xFFE0A030.toInt()) }
    val models by produceState<List<String>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.store.files(id).walk("resource_pack/models").filter { it.endsWith(".json") } }.getOrDefault(emptyList())
        }
    }
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.models_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.models_intro))
            SectionTitle(stringResource(R.string.models_new))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("model_name"), label = { Text(stringResource(R.string.models_name)) }, singleLine = true)
            ChoiceChips(ModelUse.entries, use, { it.labelFr }) { use = it; template = ModelTemplate.forUse(it).first() }
            ChoiceChips(ModelTemplate.forUse(use), template, { it.labelFr }) { template = it }
            HelpText(stringResource(if (use == ModelUse.BLOCK) R.string.models_block_help else R.string.models_creature_help))
            Text(stringResource(R.string.models_main_color))
            ColorRow(main) { main = it }
            Text(stringResource(R.string.models_accent_color))
            ColorRow(accent) { accent = it }
            val slug = Identifiers.slug(name).replace('.', '_').take(40)
            Button(onClick = {
                scope.launch(Dispatchers.Main.immediate) {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val meta = app.store.load(id)
                            val content = ModelBinding.create(meta.namespace, slug, template, main, accent)
                            val existing = app.store.files(id).conflicts(content)
                            if (existing.isNotEmpty()) throw IllegalStateException("Un modèle ou une texture « $slug » existe déjà : choisis un autre nom.")
                            ContentInstaller.install(app.store, id, content, false, "Nouveau modèle 3D")
                            app.store.touch(id)
                            ModelBinding.modelPath(template.use, slug)
                        }
                    }
                    r.onSuccess { nav.navigate(Routes.model(id, it)) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                    refresh++
                }
            }, enabled = slug.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("model_create")) { Text(stringResource(R.string.models_create)) }

            SectionTitle(stringResource(R.string.models_existing))
            val list = models
            if (list != null && list.isEmpty()) EmptyState(stringResource(R.string.models_empty))
            list?.forEach { p ->
                ActionTile(Icons.Filled.ViewInAr, p.substringAfterLast('/'), p, Modifier.fillMaxWidth()) { nav.navigate(Routes.model(id, p)) }
            }
        }
    }
}
