package com.bedrockmodstudio.ui.mods

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.mods.ModCatalog
import com.bedrockmodstudio.core.mods.QuickEdit
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.TextInputDialog
import com.bedrockmodstudio.ui.model.MeshViewport
import com.bedrockmodstudio.ui.model.OrbitCamera
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Fiche d'un contenu : grande vue 3D (rotation, zoom, animations) et modifications faciles. */
@Composable
fun ModEntryScreen(nav: NavHostController, id: String, kindName: String, entryId: String) {
    val app = LocalContext.current.container
    val files = remember(id) { app.store.files(id) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var reload by remember { mutableIntStateOf(0) }
    val kind = runCatching { ModCatalog.Kind.valueOf(kindName) }.getOrNull()

    val data by produceState<Pair<ModCatalog.Catalog, ModPreview?>?>(null, id, entryId, reload) {
        value = withContext(Dispatchers.IO) {
            val cat = runCatching { ModCatalog.scan(files) }.getOrElse { ModCatalog.Catalog(emptyList()) }
            val e = cat.byId(entryId, kind)
            cat to e?.let { runCatching { ModPreview.load(files, it) }.getOrNull() }
        }
    }
    val d = data
    if (d == null) { LoadingBox(); return }
    val entry = d.first.byId(entryId, kind)
    if (entry == null) {
        Scaffold(topBar = { BmsTopBar("Introuvable", onBack = { nav.popBackStack() }) }) { p ->
            Column(Modifier.padding(p).padding(16.dp)) { InfoCard("Ce contenu n'existe plus (supprimé ou renommé).", color = BmsColors.Warning) }
        }
        return
    }
    val preview = d.second

    var renaming by remember { mutableStateOf(false) }
    var duplicating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pending = remember(entry) { mutableStateMapOf<QuickEdit.Stat, Double>() }

    fun run(label: String, block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { block(); app.store.touch(id) } }
            r.onSuccess { ThumbCache.clear(); reload++; snackbar.showSnackbar(label) }
                .onFailure { app.errorLog.log(label, it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    Scaffold(topBar = { BmsTopBar(entry.name, subtitle = "${entry.kind.labelFr} · ${entry.id}", onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (preview != null) Viewer3D(preview)
            entry.problems.forEach { InfoCard(it, color = BmsColors.Warning) }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ renaming = true }, label = { Text("Renommer") }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                entry.texturePath?.let { t -> AssistChip({ nav.navigate(Routes.texture(id, t)) }, label = { Text("Peindre la texture") }, leadingIcon = { Icon(Icons.Filled.Brush, null) }) }
                entry.geometryPath?.let { g -> AssistChip({ nav.navigate(Routes.model(id, g)) }, label = { Text("Modifier le modèle 3D") }, leadingIcon = { Icon(Icons.Filled.ViewInAr, null) }) }
                if (entry.behaviorPath != null) AssistChip({ duplicating = true }, label = { Text("Créer une variante") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                (entry.behaviorPath ?: entry.clientPath)?.let { f -> AssistChip({ nav.navigate(Routes.edit(id, f)) }, label = { Text("Fichier JSON") }, leadingIcon = { Icon(Icons.Filled.Code, null) }) }
            }

            val stats = QuickEdit.editableStats(entry)
            if (stats.isNotEmpty() && entry.behaviorPath != null) {
                SectionTitle("Réglages")
                stats.forEach { s ->
                    val current = pending[s] ?: QuickEdit.valueOrDefault(entry, s)
                    val changed = pending[s] != null
                    Column {
                        Row {
                            Text(s.labelFr, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(fmt(current, s.integer) + if (s !in entry.stats) " (par défaut)" else "",
                                color = if (changed) BmsColors.Secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = sliderPos(current, s), onValueChange = { pending[s] = fromSlider(it, s) },
                            modifier = Modifier.testTag("stat_${s.name}"),
                        )
                        HelpText(s.helpFr)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val changes = pending.toMap()
                            run("Réglages enregistrés") { changes.forEach { (s, v) -> QuickEdit.setStat(files, entry, s, v) } }
                        },
                        enabled = pending.isNotEmpty(), modifier = Modifier.testTag("apply_stats"),
                    ) { Text("Enregistrer les réglages") }
                    if (pending.isNotEmpty()) OutlinedButton({ pending.clear() }) { Text("Annuler") }
                }
            }

            SectionTitle("Fichiers")
            listOfNotNull(entry.behaviorPath, entry.clientPath, entry.geometryPath, entry.texturePath).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.behaviorPath != null || entry.clientPath != null) {
                OutlinedButton({ deleting = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, null, tint = BmsColors.Error); Text("  Supprimer ce contenu", color = BmsColors.Error)
                }
            }
        }
    }

    if (renaming) TextInputDialog(
        title = "Nom affiché en jeu", label = "Nom", initial = entry.name,
        validate = { if (it.isBlank()) "Le nom est vide." else if (it.length > 64) "64 caractères maximum." else null },
        onConfirm = { n -> renaming = false; run("Renommé") { QuickEdit.rename(files, entry, n) } },
        onDismiss = { renaming = false },
    )
    if (duplicating) TextInputDialog(
        title = "Nouvelle variante", label = "Nom de la variante", initial = "${entry.name} 2",
        validate = { if (it.isBlank()) "Le nom est vide." else null },
        onConfirm = { n ->
            duplicating = false
            scope.launch(Dispatchers.Main.immediate) {
                val r = withContext(Dispatchers.IO) { runCatching { QuickEdit.duplicate(files, d.first, entry, n).also { app.store.touch(id) } } }
                r.onSuccess { newId -> ThumbCache.clear(); nav.navigate(Routes.modEntry(id, entry.kind.name, newId)) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
            }
        },
        onDismiss = { duplicating = false },
    )
    if (deleting) ConfirmDialog(
        title = "Supprimer « ${entry.name} » ?",
        body = "Ses fichiers de comportement et d'apparence vont dans la corbeille du projet (récupérables). Le modèle et la texture sont gardés, car d'autres contenus peuvent les utiliser.",
        destructive = true,
        onConfirm = {
            deleting = false
            scope.launch(Dispatchers.Main.immediate) {
                withContext(Dispatchers.IO) { runCatching { QuickEdit.delete(files, entry); app.store.touch(id) } }
                ThumbCache.clear(); nav.popBackStack()
            }
        },
        onDismiss = { deleting = false },
    )
}

/** Vue 3D avec rotation automatique et lecture des animations du modèle. */
@Composable
private fun Viewer3D(preview: ModPreview) {
    val camera = remember(preview) { OrbitCamera().also { it.frame(preview.faces()) } }
    var auto by remember { mutableStateOf(true) }
    var anim by remember(preview) { mutableStateOf(preview.animations.firstOrNull { it.loop || it.name.contains("walk") || it.name.contains("move") }) }
    var time by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(auto, anim) {
        var last = 0L
        while (auto || anim != null) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9
                    if (auto) camera.yaw += (dt * 30).toFloat()
                    anim?.let { a -> time = if (a.length > 0) (time + dt) % maxOf(a.length, 0.05) else 0.0 }
                }
                last = now
            }
        }
    }
    val poses = anim?.sample(time) ?: emptyMap()
    val faces = remember(preview, poses) { preview.faces(poses) }
    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))) {
        MeshViewport(
            faces, preview.texture, preview.textureWidth, preview.textureHeight, camera,
            lighting = true, lightAngle = 45f, modifier = Modifier.fillMaxSize(), faceColor = preview.faceColor,
        ) { auto = false }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(auto, { auto = !auto }, label = { Text("Rotation") }, leadingIcon = { Icon(Icons.Filled.Autorenew, null) })
        if (preview.animations.isNotEmpty()) {
            FilterChip(anim == null, { anim = null; time = 0.0 }, label = { Text("Immobile") })
            preview.animations.forEach { a -> FilterChip(anim == a, { anim = a; time = 0.0 }, label = { Text(a.name) }) }
        }
    }
    HelpText("Un doigt : tourner · deux doigts : zoomer et déplacer.")
}

private fun fmt(v: Double, integer: Boolean) = if (integer) Math.round(v).toString() else String.format(Locale.FRANCE, "%.2f", v)

/** Curseur non linéaire pour les grandes plages (1 à 1000 PV) : précis pour les petites valeurs. */
private fun sliderPos(v: Double, s: QuickEdit.Stat): Float {
    val t = ((v - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0)
    return if (s.max - s.min > 100) Math.sqrt(t).toFloat() else t.toFloat()
}

private fun fromSlider(p: Float, s: QuickEdit.Stat): Double {
    val t = if (s.max - s.min > 100) (p * p).toDouble() else p.toDouble()
    val v = s.min + t * (s.max - s.min)
    return if (s.integer) Math.round(v).toDouble() else Math.round(v * 100) / 100.0
}
