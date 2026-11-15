package com.bedrockmodstudio.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.server.CloudServer
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun statusColor(s: CloudServer): Color = when {
    s.status == "not_installed" -> BmsColors.TextMuted
    !s.agentOnline -> BmsColors.TextMuted
    s.status == "online" -> BmsColors.Primary
    s.status in setOf("starting", "stopping", "installing") -> BmsColors.Secondary
    s.status in setOf("crashed", "error") -> BmsColors.Error
    else -> BmsColors.Info
}

/**
 * Onglet « Serveurs » : héberger un vrai serveur Minecraft Bedrock (jusqu'à 5 joueurs) avec tes mods.
 * Supabase (gratuit, ton propre projet) sert de tableau de bord ; le jeu tourne sur une machine où l'agent est installé.
 */
@Composable
fun ServersScreen(nav: NavHostController) {
    val app = LocalContext.current.container
    val cloud = app.cloud
    var stage by remember { mutableIntStateOf(0) } // force le recalcul après configuration / connexion
    val configured = remember(stage) { cloud.configured }
    val loggedIn = remember(stage) { cloud.session != null }

    Scaffold(
        topBar = {
            BmsTopBar("Serveurs", subtitle = if (loggedIn) cloud.session?.email else "Minecraft Bedrock · 5 joueurs", actions = {
                if (configured) IconButton({ cloud.url = ""; cloud.anonKey = ""; cloud.signOut(); stage++ }) { Icon(Icons.Filled.Settings, "Changer de projet Supabase") }
                if (loggedIn) IconButton({ cloud.signOut(); stage++ }) { Icon(Icons.AutoMirrored.Filled.Logout, "Se déconnecter") }
            })
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !configured -> SetupForm { stage++ }
                !loggedIn -> LoginForm { stage++ }
                else -> ServerList(nav)
            }
        }
    }
}

@Composable
private fun SetupForm(onDone: () -> Unit) {
    val app = LocalContext.current.container
    var url by remember { mutableStateOf(app.cloud.url) }
    var key by remember { mutableStateOf(app.cloud.anonKey) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard("Héberge un vrai serveur Minecraft Bedrock pour toi et tes amis (5 joueurs maximum), avec tous les mods de tes projets, gratuitement.")
        SectionTitle("Comment ça marche")
        HelpText("1. Supabase (gratuit) sert de tableau de bord : comptes, liste des serveurs, console, envoi des mods.")
        HelpText("2. Le jeu tourne sur une machine allumée en permanence : une machine virtuelle gratuite (Oracle Cloud « Always Free » : 4 cœurs ARM et 24 Go de mémoire) ou un vieux PC sous Linux. Une seule commande à copier l'installe.")
        HelpText("3. Tu pilotes tout d'ici : démarrer, arrêter, ajouter des mods, sauvegarder, console.")
        InfoCard("Supabase seul ne peut pas faire tourner le jeu : ses fonctions s'arrêtent au bout de quelques secondes et n'ouvrent pas de port UDP. C'est pour ça qu'il faut une machine en plus.", color = BmsColors.Warning)
        SectionTitle("Ton projet Supabase")
        HelpText("Crée un projet gratuit sur supabase.com, exécute le script SQL fourni (server/supabase/migrations dans le dépôt de l'application, ou suis docs/HEBERGEMENT.md), puis copie ici l'adresse du projet et la clé « anon » (Project Settings → API).")
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("cloud_url"), label = { Text("Adresse (https://xxxx.supabase.co)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("cloud_key"), label = { Text("Clé anon (publique)") }, singleLine = true)
        val valid = url.trim().startsWith("https://") && key.trim().length > 20
        if (key.contains("service_role")) InfoCard("N'utilise JAMAIS la clé « service_role » dans l'application : prends la clé « anon ».", color = BmsColors.Error)
        Button({ app.cloud.url = url; app.cloud.anonKey = key; onDone() }, enabled = valid && !key.contains("service_role"), modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
    }
}

@Composable
private fun LoginForm(onDone: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go(signup: Boolean) {
        busy = true; error = null; info = null
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { if (signup) app.cloud.signUp(email, password) else { app.cloud.signIn(email, password); true } } }
            busy = false
            r.onSuccess { ready -> if (ready) onDone() else info = "Compte créé. Confirme ton adresse e-mail (lien reçu), puis connecte-toi." }
                .onFailure { error = it.message }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Connexion")
        HelpText("Ce compte appartient à TON projet Supabase : il protège tes serveurs (personne d'autre ne peut les piloter).")
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Adresse e-mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mot de passe (8 caractères minimum)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { InfoCard(it, color = BmsColors.Error) }
        info?.let { InfoCard(it) }
        val ok = !busy && email.contains('@') && password.isNotEmpty()
        Button({ go(false) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Patiente…" else "Se connecter") }
        OutlinedButton({ go(true) }, enabled = ok && password.length >= 8, modifier = Modifier.fillMaxWidth()) { Text("Créer un compte") }
    }
}

@Composable
private fun ServerList(nav: NavHostController) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var servers by remember { mutableStateOf<List<CloudServer>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { app.servers.servers() } }
            r.onSuccess { servers = it; error = null }.onFailure { error = it.message }
            delay(5000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { InfoCard(it, color = BmsColors.Error) }
            val list = servers
            if (list != null && list.isEmpty()) {
                EmptyState("Aucun serveur pour l'instant.")
                HelpText("Touche « Nouveau serveur » : tu obtiendras une commande à coller sur ta machine (VM gratuite ou PC Linux) pour l'installer.")
            }
            list?.forEach { s ->
                Card(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.server(s.id)) }.testTag("server_card"),
                    shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Dns, null, tint = statusColor(s), modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${CloudServer.GAMEMODES.firstOrNull { it.first == s.gamemode }?.second ?: s.gamemode} · ${s.players.size}/${s.maxPlayers} joueurs" +
                                    if (s.address.isNotEmpty()) " · ${s.address}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.size(10.dp).background(statusColor(s), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(s.statusFr, style = MaterialTheme.typography.labelMedium, color = statusColor(s))
                    }
                }
            }
            Spacer(Modifier.size(80.dp))
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nouveau serveur") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("new_server"),
        )
    }
    if (creating) CreateServerDialog(onDismiss = { creating = false }) { name, mode, diff, players, seed, beta ->
        creating = false
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { app.servers.create(name, mode, diff, players, seed, beta) } }
            r.onSuccess { tick++; nav.navigate(Routes.server(it.id)) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }
}

@Composable
private fun CreateServerDialog(onDismiss: () -> Unit, onCreate: (String, String, String, Int, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("Mon serveur") }
    var mode by remember { mutableStateOf("survival") }
    var diff by remember { mutableStateOf("normal") }
    var players by remember { mutableFloatStateOf(5f) }
    var seed by remember { mutableStateOf("") }
    var beta by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau serveur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(48) }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true)
                Text("Mode de jeu", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(CloudServer.GAMEMODES.map { it.first }, mode, { k -> CloudServer.GAMEMODES.first { it.first == k }.second }) { mode = it }
                Text("Difficulté", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(CloudServer.DIFFICULTIES.map { it.first }, diff, { k -> CloudServer.DIFFICULTIES.first { it.first == k }.second }) { diff = it }
                Text("Joueurs maximum : ${players.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(players, { players = it }, valueRange = 1f..5f, steps = 3)
                OutlinedTextField(seed, { seed = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("Graine du monde (facultatif)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("API bêta (scripts expérimentaux)")
                        HelpText("À activer si un de tes mods utilise une version « beta » de @minecraft/server.")
                    }
                    Switch(beta, { beta = it })
                }
            }
        },
        confirmButton = { TextButton({ onCreate(name, mode, diff, players.toInt(), seed, beta) }, enabled = name.isNotBlank()) { Text("Créer") } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } },
    )
}
