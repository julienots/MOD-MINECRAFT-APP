package com.bedrockmodstudio.ui.mods

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.mods.ModCatalog
import com.bedrockmodstudio.core.mods.PackMerger
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.importing.displayName
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Petit cache de vignettes 3D par contenu (clé : fichier + date de modification). */
internal object ThumbCache {
    private val map = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 120
    }
    fun get(k: String) = synchronized(map) { map[k] }
    fun put(k: String, b: Bitmap) = synchronized(map) { map[k] = b }
    fun clear() = synchronized(map) { map.clear() }
}

/**
 * « Contenu du mod » : toutes les créatures, objets et blocs du projet (créé ici ou importé) en vignettes 3D.
 * Un toucher ouvre la fiche (vue 3D, animations, réglages simples). Le bouton « Ajouter un mod »
 * fusionne un addon existant dans ce projet pour combiner plusieurs mods.
 */
@Composable
fun ModContentScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var reload by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<ModCatalog.Kind?>(null) }
    var query by remember { mutableStateOf("") }
    var askPolicy by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(PackMerger.Policy.KEEP_MINE) }
    var merging by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<PackMerger.Report?>(null) }

    val catalog by produceState<ModCatalog.Catalog?>(null, id, reload) {
        value = withContext(Dispatchers.IO) { runCatching { ModCatalog.scan(app.store.files(id)) }.getOrElse { ModCatalog.Catalog(emptyList()) } }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        merging = true
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(context, uri)
                    context.contentResolver.openInputStream(uri)?.use {
                        PackMerger(app.store, java.io.File(context.cacheDir, "merge")).merge(id, it, name, policy)
                    } ?: throw IllegalStateException("Impossible de lire le fichier choisi.")
                }.onFailure { app.errorLog.log("Ajout d'un mod", it) }
            }
            merging = false
            r.onSuccess { report = it; ThumbCache.clear(); reload++ }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    Scaffold(
        topBar = { BmsTopBar("Contenu du mod", subtitle = catalog?.let { "${it.entries.size} éléments" }, onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { askPolicy = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Ajouter un mod") },
                modifier = Modifier.testTag("merge_mod"),
            )
        },
    ) { padding ->
        val cat = catalog
        if (cat == null || merging) { Box(Modifier.padding(padding)) { LoadingBox(if (merging) "Ajout du mod…" else "Analyse du mod…") }; return@Scaffold }
        val shown = cat.entries.filter { (filter == null || it.kind == filter) && (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Chercher (nom ou identifiant)") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        FilterChip(filter == null, { filter = null }, label = { Text("Tout") })
                        ModCatalog.Kind.entries.forEach { k ->
                            FilterChip(filter == k, { filter = if (filter == k) null else k }, label = { Text("${k.pluralFr} ${cat.of(k).size}") })
                        }
                    }
                }
            }
            if (cat.entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EmptyState("Ce projet ne contient encore aucune créature, aucun objet ni aucun bloc.")
                    HelpText("Crée-en avec les assistants du projet, ou touche « Ajouter un mod » pour importer un addon existant (.mcaddon / .mcpack) dans ce projet.")
                }
            }
            items(shown, key = { "${it.kind}|${it.id}" }) { e ->
                ModCard(app.store.files(id), e) { nav.navigate(Routes.modEntry(id, e.kind.name, e.id)) }
            }
        }
    }

    if (askPolicy) AlertDialog(
        onDismissRequest = { askPolicy = false },
        title = { Text("Ajouter un mod existant") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choisis un .mcaddon, .mcpack ou .zip : ses créatures, objets, blocs, textures, sons et scripts sont ajoutés à CE projet. Tu pourras ensuite tout modifier ici et exporter un seul addon.")
                SectionTitle("Si un fichier existe déjà")
                PackMerger.Policy.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { policy = p }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(policy == p, { policy = p }); Text(p.labelFr, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HelpText("Les traductions et les listes de textures sont toujours fusionnées sans rien effacer.")
            }
        },
        confirmButton = { TextButton({ askPolicy = false; picker.launch(arrayOf("*/*")) }) { Text("Choisir le fichier") } },
        dismissButton = { TextButton({ askPolicy = false }) { Text("Annuler") } },
    )

    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Mod ajouté") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.packNames.joinToString(" + ")}")
                    Text("${r.added} fichier(s) ajouté(s), ${r.replaced} remplacé(s), ${r.identical} déjà identique(s).")
                    if (r.newContent.isNotEmpty()) { SectionTitle("Nouveau contenu (${r.newContent.size})"); r.newContent.take(30).forEach { Text("• $it") } }
                    if (r.mergedShared.isNotEmpty()) Text("Fusionné : ${r.mergedShared.joinToString { it.substringAfterLast('/') }}")
                    r.scriptEntry?.let { Text("Scripts du mod branchés : $it") }
                    if (r.duplicateIds.isNotEmpty()) InfoCard("Identifiants en double (une seule définition sera utilisée en jeu) :\n" + r.duplicateIds.take(8).joinToString("\n"), color = BmsColors.Warning)
                    r.notes.forEach { InfoCard(it, color = BmsColors.Warning) }
                    HelpText("Lance « Vérifier » dans le projet pour un contrôle complet.")
                }
            },
            confirmButton = { TextButton({ report = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ModCard(files: com.bedrockmodstudio.core.storage.ProjectFiles, e: ModCatalog.Entry, onClick: () -> Unit) {
    val key = "${e.kind}|${e.id}|${listOfNotNull(e.behaviorPath, e.clientPath, e.texturePath, e.geometryPath).sumOf { runCatching { files.resolve(it, true).lastModified() }.getOrDefault(0L) }}"
    var thumb by remember(key) { mutableStateOf(ThumbCache.get(key)) }
    LaunchedEffect(key) {
        if (thumb == null) thumb = withContext(Dispatchers.Default) {
            runCatching { ModPreview.load(files, e).thumbnail(256) }.getOrNull()?.also { ThumbCache.put(key, it) }
        }
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("mod_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t.asImageBitmap(), e.name, Modifier.fillMaxSize(), filterQuality = FilterQuality.Low)
            else CircularProgressIndicator(Modifier.size(28.dp))
            if (e.problems.isNotEmpty()) Icon(Icons.Filled.Warning, "Problème", tint = BmsColors.Warning, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            Icon(
                when (e.kind) { ModCatalog.Kind.ENTITY -> Icons.Filled.Pets; ModCatalog.Kind.ITEM -> Icons.Filled.Hardware; ModCatalog.Kind.BLOCK -> Icons.Filled.ViewInAr },
                e.kind.labelFr, tint = BmsColors.TextMuted, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp),
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
