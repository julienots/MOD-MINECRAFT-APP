package com.bedrockmodstudio.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.server.CloudPack
import com.bedrockmodstudio.server.CloudServer
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ServerDetailScreen(nav: NavHostController, serverId: String) {
    val context = LocalContext.current
    val app = context.container
    val repo = app.servers
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val clipboard = LocalClipboardManager.current

    var server by remember { mutableStateOf<CloudServer?>(null) }
    var packs by remember { mutableStateOf<List<CloudPack>>(emptyList()) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var agentKey by remember { mutableStateOf<String?>(null) }
    var addingProject by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var newKeyConfirm by remember { mutableStateOf(false) }
    var consoleInput by remember { mutableStateOf("") }

    LaunchedEffect(serverId) { agentKey = withContext(Dispatchers.IO) { runCatching { repo.agentKey(serverId) }.getOrNull() } }
    LaunchedEffect(serverId, refresh) {
        while (true) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val s = repo.server(serverId)
                    Triple(s, repo.packs(serverId), repo.logs(serverId)) to repo.recentCommands(serverId).firstOrNull()
                }
            }
            r.onSuccess { (t, cmd) ->
                server = t.first; packs = t.second; logs = t.third; error = null
                lastResult = cmd?.let { c ->
                    val state = when (c.str("state")) { "pending" -> "en attente de la machine"; "running" -> "en cours"; "done" -> "fait"; "error" -> "ERREUR"; else -> "" }
                    "Dernière action « ${c.str("kind")} » : $state${c.str("result")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}"
                }
            }.onFailure { error = it.message }
            delay(3000)
        }
    }

    fun act(label: String, block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            busy = label
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = null
            r.onSuccess { refresh++; snackbar.showSnackbar(label) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    val s = server
    Scaffold(topBar = { BmsTopBar(s?.name ?: "Serveur", subtitle = s?.statusFr, onBack = { nav.popBackStack() }) }) { padding ->
        if (s == null) { Box(Modifier.padding(padding)) { if (error != null) InfoCard(error!!, color = BmsColors.Error) else LoadingBox() }; return@Scaffold }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { InfoCard(it, color = BmsColors.Error) }

            // ---- État
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(statusColor(s), CircleShape))
                        Text("  ${s.statusFr}", style = MaterialTheme.typography.titleMedium, color = statusColor(s))
                    }
                    if (s.message.isNotBlank()) Text(s.message, style = MaterialTheme.typography.bodySmall)
                    if (s.address.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Adresse : ${s.address}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton({ clipboard.setText(AnnotatedString(s.address)) }) { Icon(Icons.Filled.ContentCopy, "Copier l'adresse") }
                    }
                    if (s.address.isNotBlank()) HelpText("Dans Minecraft : Jouer → Serveurs → Ajouter un serveur → adresse ${s.address.substringBeforeLast(':')} et port ${s.address.substringAfterLast(':')}.")
                    val m = s.metrics
                    if (m.entries.isNotEmpty()) Text(
                        listOfNotNull(
                            m["load"]?.asDouble?.let { "Charge ${"%.2f".format(it)} / ${m["cpus"]?.asInt ?: "?"} cœurs" },
                            m["mem_used_mb"]?.asInt?.let { "Mémoire ${it} / ${m["mem_total_mb"]?.asInt ?: "?"} Mo" },
                            m["disk_free_gb"]?.asDouble?.let { "Disque libre $it Go" },
                            s.version.takeIf { it.isNotBlank() }?.let { "Bedrock $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.players.isNotEmpty()) Text("Joueurs : ${s.players.joinToString()}", style = MaterialTheme.typography.bodyMedium)
                    lastResult?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            // ---- Actions
            val canAct = s.agentOnline && busy == null
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ act("Démarrage demandé") { repo.command(serverId, "start") } }, enabled = canAct && s.status != "online", modifier = Modifier.testTag("server_start")) {
                    Icon(Icons.Filled.PlayArrow, null); Text(" Démarrer")
                }
                FilledTonalButton({ act("Arrêt demandé") { repo.command(serverId, "stop") } }, enabled = canAct && s.status == "online") { Icon(Icons.Filled.Stop, null); Text(" Arrêter") }
                FilledTonalButton({ act("Redémarrage demandé") { repo.command(serverId, "restart") } }, enabled = canAct) { Icon(Icons.Filled.RestartAlt, null); Text(" Redémarrer") }
                FilledTonalButton({ act("Sauvegarde demandée") { repo.command(serverId, "backup") } }, enabled = canAct) { Icon(Icons.Filled.Save, null); Text(" Sauvegarder") }
            }
            if (!s.agentOnline) InfoCard(
                if (s.status == "not_installed") "Ce serveur n'est pas encore installé sur une machine : suis l'étape « Installation » plus bas."
                else "La machine ne répond plus (éteinte, sans Internet, ou agent arrêté). Les actions seront exécutées dès son retour.",
                color = BmsColors.Warning,
            )
            if (s.backups > 0) HelpText("${s.backups} sauvegarde(s) du monde gardée(s) sur la machine (dossier /opt/bms/backups, les 5 plus récentes).")

            // ---- Mods
            SectionTitle("Mods installés (${packs.size})")
            HelpText("Ajoute tes projets : ils sont vérifiés, empaquetés et envoyés. Les joueurs reçoivent automatiquement les textures en se connectant. L'ordre compte : le premier pack de la liste est prioritaire en cas de conflit.")
            packs.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, color = if (p.enabled) MaterialTheme.colorScheme.onSurface else BmsColors.TextMuted)
                        Text("${if (p.kind == "behavior") "Comportements" else "Apparence"} · ${p.size / 1024} Ko", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ act("Ordre modifié") { repo.setPack(p, position = i - 1); packs.getOrNull(i - 1)?.let { repo.setPack(it, position = i) } } }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "Monter") }
                    IconButton({ act("Ordre modifié") { repo.setPack(p, position = i + 1); packs.getOrNull(i + 1)?.let { repo.setPack(it, position = i) } } }, enabled = i < packs.size - 1) { Icon(Icons.Filled.ArrowDownward, "Descendre") }
                    Switch(p.enabled, { v -> act(if (v) "Pack activé" else "Pack désactivé") { repo.setPack(p, enabled = v) } })
                    IconButton({ act("Pack retiré") { repo.removePack(p) } }) { Icon(Icons.Filled.Delete, "Retirer", tint = BmsColors.Error) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ addingProject = true }, enabled = busy == null) { Text("Ajouter un projet") }
                Button({ act("Mods appliqués (le serveur redémarre)") { repo.command(serverId, "sync_packs") } }, enabled = canAct) { Text("Appliquer les mods") }
            }
            busy?.let { Text("$it…", style = MaterialTheme.typography.labelSmall) }

            // ---- Réglages
            SettingsSection(s) { settings -> act("Réglages enregistrés (appliqués au prochain démarrage)") { repo.updateSettings(serverId, settings); repo.command(serverId, "apply_settings") } }

            // ---- Console
            SectionTitle("Console")
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp),
                ) {
                    if (logs.isEmpty()) Text("(vide)", color = BmsColors.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    logs.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (it.contains("ERROR", true)) BmsColors.Error else MaterialTheme.colorScheme.onSurface) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(consoleInput, { consoleInput = it.take(500) }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("say Bonjour · op Pseudo · time set day") })
                IconButton({ val c = consoleInput; consoleInput = ""; act("Commande envoyée") { repo.command(serverId, "command", c) } },
                    enabled = consoleInput.isNotBlank() && s.status == "online") { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
            }

            // ---- Installation
            SectionTitle("Installation sur une machine")
            HelpText("Machine conseillée (gratuite) : Oracle Cloud « Always Free », instance Ampere A1 avec 4 cœurs et 24 Go de mémoire, système Ubuntu 22.04 ou 24.04. Bien plus puissante qu'un serveur Aternos, sans file d'attente, allumée en permanence. Un vieux PC sous Ubuntu fonctionne aussi.")
            HelpText("Connecte-toi à la machine en SSH puis colle cette commande. Elle installe tout (et box64 si la machine est en ARM), te demande d'accepter les conditions de Minecraft, puis démarre l'agent.")
            val key = agentKey
            if (key != null) {
                val cmd = repo.installCommand(serverId, key)
                SelectionContainer {
                    Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp)).padding(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ clipboard.setText(AnnotatedString(cmd)); scope.launch { snackbar.showSnackbar("Commande copiée") } }) { Icon(Icons.Filled.ContentCopy, null); Text(" Copier la commande") }
                }
                InfoCard("Cette commande contient la clé secrète de ce serveur : ne la partage pas.", color = BmsColors.Warning)
            } else {
                InfoCard("La clé de ce serveur n'est pas enregistrée sur ce téléphone (serveur créé ailleurs). Génère une nouvelle clé pour obtenir la commande.", color = BmsColors.Warning)
            }
            OutlinedButton({ newKeyConfirm = true }) { Text("Générer une nouvelle clé") }
            HelpText("Port à ouvrir : UDP ${s.port} (et ${s.port + 1} pour l'IPv6). Sur Oracle Cloud, ajoute une règle d'entrée UDP ${s.port} dans la « Security List » du réseau.")

            OutlinedButton({ deleting = true }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Delete, null, tint = BmsColors.Error); Text("  Supprimer ce serveur", color = BmsColors.Error) }
        }
    }

    if (addingProject) {
        val projects = remember { app.store.list() }
        AlertDialog(
            onDismissRequest = { addingProject = false },
            title = { Text("Ajouter un projet au serveur") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (projects.isEmpty()) Text("Aucun projet. Crée ou importe un mod d'abord.")
                    HelpText("Astuce : pour combiner plusieurs mods existants, importe-les dans un projet (Contenu du mod → Ajouter un mod), ou ajoute-les ici un par un.")
                    projects.forEach { p ->
                        Text(p.meta.name, Modifier.fillMaxWidth().clickable {
                            addingProject = false
                            act("« ${p.meta.name} » envoyé au serveur") { repo.addProject(serverId, app.store, p.meta.id, File(context.cacheDir, "server_upload")) }
                        }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = { TextButton({ addingProject = false }) { Text("Fermer") } },
        )
    }
    if (newKeyConfirm) ConfirmDialog(
        title = "Nouvelle clé ?",
        body = "L'ancienne machine ne pourra plus piloter ce serveur tant que tu n'auras pas relancé la nouvelle commande d'installation dessus.",
        onConfirm = { newKeyConfirm = false; scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { repo.regenerateKey(serverId) } }
            r.onSuccess { agentKey = it }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
        } },
        onDismiss = { newKeyConfirm = false },
    )
    if (deleting) ConfirmDialog(
        title = "Supprimer « ${s?.name} » ?",
        body = "Le serveur disparaît du tableau de bord et les mods envoyés sont effacés de Supabase. Le monde reste sur la machine (dossier /opt/bms) : arrête l'agent avec « sudo systemctl disable --now bms-agent ».",
        destructive = true,
        onConfirm = { deleting = false; scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { repo.deleteServer(serverId) } }
            r.onSuccess { nav.popBackStack() }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
        } },
        onDismiss = { deleting = false },
    )
}

@Composable
private fun SettingsSection(s: CloudServer, onSave: (com.bedrockmodstudio.core.json.JsonObject) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var mode by remember(s.id) { mutableStateOf(s.gamemode) }
    var diff by remember(s.id) { mutableStateOf(s.difficulty) }
    var players by remember(s.id) { mutableFloatStateOf(s.maxPlayers.toFloat()) }
    var view by remember(s.id) { mutableFloatStateOf(s.viewDistance.toFloat()) }
    var tick by remember(s.id) { mutableFloatStateOf(s.tickDistance.toFloat()) }
    var cheats by remember(s.id) { mutableStateOf(s.allowCheats) }
    var beta by remember(s.id) { mutableStateOf(s.betaApis) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Réglages du monde", Modifier.weight(1f)); Text(if (open) "Masquer" else "Modifier", color = BmsColors.Primary)
    }
    if (!open) return
    Text("Mode de jeu", style = MaterialTheme.typography.labelLarge)
    ChoiceChips(CloudServer.GAMEMODES.map { it.first }, mode, { k -> CloudServer.GAMEMODES.first { it.first == k }.second }) { mode = it }
    Text("Difficulté", style = MaterialTheme.typography.labelLarge)
    ChoiceChips(CloudServer.DIFFICULTIES.map { it.first }, diff, { k -> CloudServer.DIFFICULTIES.first { it.first == k }.second }) { diff = it }
    Text("Joueurs maximum : ${players.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(players, { players = it }, valueRange = 1f..5f, steps = 3)
    Text("Distance d'affichage : ${view.toInt()} tronçons", style = MaterialTheme.typography.labelLarge)
    Slider(view, { view = it }, valueRange = 5f..32f)
    HelpText("Plus c'est grand, plus on voit loin… et plus la machine travaille. 12 à 16 est confortable à 5 joueurs sur Oracle Ampere.")
    Text("Distance de simulation : ${tick.toInt()} tronçons", style = MaterialTheme.typography.labelLarge)
    Slider(tick, { tick = it }, valueRange = 4f..12f)
    HelpText("Zone où les créatures, les cultures et les machines fonctionnent autour des joueurs. Avec beaucoup de mods, reste entre 4 et 6.")
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Commandes de triche autorisées", Modifier.weight(1f)); Switch(cheats, { cheats = it }) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("API bêta (scripts expérimentaux)", Modifier.weight(1f)); Switch(beta, { beta = it }) }
    Button({
        onSave(obj("gamemode" to mode, "difficulty" to diff, "max_players" to players.toInt(), "view_distance" to view.toInt(),
            "tick_distance" to tick.toInt(), "allow_cheats" to cheats, "beta_apis" to beta))
        open = false
    }, Modifier.fillMaxWidth()) { Text("Enregistrer et appliquer") }
}
