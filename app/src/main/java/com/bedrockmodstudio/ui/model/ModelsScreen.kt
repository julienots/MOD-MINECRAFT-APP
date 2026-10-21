package com.bedrockmodstudio.ui.model

import androidx.compose.foundation.clickable
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
import com.bedrockmodstudio.core.image.PixelImage
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.GeoFile
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val models by produceState<List<String>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.store.files(id).walk("resource_pack/models").filter { it.endsWith(".json") } }.getOrDefault(emptyList())
        }
    }
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.models_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.models_intro))
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_new)) }
            val list = models
            if (list != null && list.isEmpty()) EmptyState(stringResource(R.string.models_empty))
            list?.forEach { p ->
                ActionTile(Icons.Filled.ViewInAr, p.substringAfterLast('/'), p, Modifier.fillMaxWidth()) { nav.navigate(Routes.model(id, p)) }
            }
        }
    }
    if (creating) TextInputDialog(
        stringResource(R.string.models_new), stringResource(R.string.file_name),
        validate = { n -> val s = Identifiers.slug(n).replace('.', '_'); if (s.isEmpty()) "Nom invalide." else ProjectFiles.validateName("$s.geo.json") },
        onConfirm = { n ->
            creating = false
            val name = Identifiers.slug(n).replace('.', '_')
            scope.launch(Dispatchers.Main.immediate) {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val meta = app.store.load(id)
                        val files = app.store.files(id)
                        val path = "resource_pack/models/entity/$name.geo.json"
                        if (files.exists(path)) throw IllegalStateException("Le modèle « $name » existe déjà.")
                        val model = GeoCodec.starter("geometry.${meta.namespace}.$name")
                        files.writeText(path, GeoCodec.write(GeoFile("1.12.0", listOf(model), legacy = false)), "Nouveau modèle")
                        val tex = "resource_pack/textures/entity/$name.png"
                        if (!files.exists(tex)) files.write(tex, PixelImage(64, 64).also { img -> img.pixels.fill(0xFF8FA3B8.toInt()) }.toPng(), "Nouveau modèle")
                        path
                    }
                }
                r.onSuccess { nav.navigate(Routes.model(id, it)) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                refresh++
            }
        },
        onDismiss = { creating = false },
    )
}
