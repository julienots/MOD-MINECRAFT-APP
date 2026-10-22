package com.bedrockmodstudio.ui.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.image.PixelImage
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dossiers de textures Bedrock, dans l'ordre d'affichage. */
private enum class TextureGroup(val dir: String, val labelFr: String) {
    ENTITY("entity", "Créatures et modèles 3D"), ITEMS("items", "Objets"), BLOCKS("blocks", "Blocs"),
    PARTICLE("particle", "Particules"), OTHER("", "Autres images"),
}

private class Thumb(val path: String, val bitmap: Bitmap?, val width: Int, val height: Int)

/** Galerie de toutes les textures du projet : un toucher ouvre l'éditeur de textures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TexturesScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(TextureGroup.ITEMS) }
    var size by remember { mutableStateOf(16) }
    val thumbs by produceState<Map<TextureGroup, List<Thumb>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val files = app.store.files(id)
                files.walk("resource_pack/textures").filter { it.endsWith(".png", ignoreCase = true) }.map { p ->
                    val bytes = runCatching { files.readBytes(p) }.getOrNull()
                    val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                    Thumb(p, bmp, bmp?.width ?: 0, bmp?.height ?: 0)
                }.groupBy { t ->
                    val sub = t.path.removePrefix("resource_pack/textures/").substringBefore('/', "")
                    TextureGroup.entries.firstOrNull { it.dir.isNotEmpty() && it.dir == sub } ?: TextureGroup.OTHER
                }
            }.getOrDefault(emptyMap())
        }
    }
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.textures_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.textures_intro))
            val all = thumbs
            if (all != null && all.isEmpty()) EmptyState(stringResource(R.string.textures_empty))
            all?.let { map ->
                TextureGroup.entries.forEach { g ->
                    val list = map[g].orEmpty()
                    if (list.isEmpty()) return@forEach
                    SectionTitle("${g.labelFr} (${list.size})")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { t ->
                            Column(Modifier.width(96.dp).clickable { nav.navigate(Routes.texture(id, t.path)) }.testTag("texture_thumb")) {
                                if (t.bitmap != null) Image(t.bitmap.asImageBitmap(), t.path, filterQuality = FilterQuality.None,
                                    modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                                Text(t.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.width} × ${t.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            SectionTitle(stringResource(R.string.textures_new))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("texture_new_name"), label = { Text(stringResource(R.string.textures_name)) }, singleLine = true)
            ChoiceChips(TextureGroup.entries.filter { it != TextureGroup.OTHER }, group, { it.labelFr }) { group = it }
            ChoiceChips(listOf(16, 32, 64, 128), size, { "$it × $it" }) { size = it }
            HelpText(stringResource(R.string.textures_new_help))
            val slug = Identifiers.slug(name).replace('.', '_').take(40)
            Button(onClick = {
                val path = "resource_pack/textures/${group.dir}/$slug.png"
                scope.launch(Dispatchers.Main.immediate) {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val files = app.store.files(id)
                            if (files.exists(path)) throw IllegalStateException("La texture « $slug.png » existe déjà : touche-la dans la galerie pour la modifier.")
                            files.createFile(path, PixelImage(size, size).toPng())
                            app.store.touch(id)
                        }
                    }
                    r.onSuccess { nav.navigate(Routes.texture(id, path)) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                }
            }, enabled = slug.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.textures_create)) }
        }
    }
}
