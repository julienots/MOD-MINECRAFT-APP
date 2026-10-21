package com.bedrockmodstudio.ui.logic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.logic.BehaviorCatalog
import com.bedrockmodstudio.core.logic.BehaviorCompiler
import com.bedrockmodstudio.core.logic.BehaviorRule
import com.bedrockmodstudio.core.logic.BehaviorRulesFile
import com.bedrockmodstudio.core.logic.BlockCategory
import com.bedrockmodstudio.core.logic.BlockDef
import com.bedrockmodstudio.core.logic.BlockInstance
import com.bedrockmodstudio.core.logic.ParamType
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.ItemField
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun categoryColor(c: BlockCategory) = when (c) {
    BlockCategory.EVENT -> Color(0xFFE09F3E)
    BlockCategory.CONDITION -> Color(0xFF4C9BE8)
    BlockCategory.ACTION -> Color(0xFF5FC27E)
}

/** Règles d'exemple proposées pour démarrer. */
private fun examples(ns: String) = listOf(
    BehaviorRule("Message de bienvenue", BlockInstance("player_join"), listOf(BlockInstance("not_tag", mapOf("tag" to "accueilli"))),
        listOf(BlockInstance("message", mapOf("text" to "Bienvenue dans le monde !")), BlockInstance("give", mapOf("item" to "minecraft:bread", "count" to "5")),
            BlockInstance("add_tag", mapOf("tag" to "accueilli")))),
    BehaviorRule("Bâton de vitesse", BlockInstance("item_use", mapOf("item" to "minecraft:stick")), listOf(BlockInstance("sneaking")),
        listOf(BlockInstance("effect", mapOf("effect" to "speed", "seconds" to "10", "level" to "2")), BlockInstance("sound", mapOf("sound" to "random.levelup")))),
    BehaviorRule("Minerai chanceux", BlockInstance("block_break", mapOf("block" to "minecraft:stone")), listOf(BlockInstance("chance", mapOf("percent" to "5"))),
        listOf(BlockInstance("give", mapOf("item" to "minecraft:emerald", "count" to "1")), BlockInstance("message", mapOf("text" to "Une émeraude cachée !")))),
    BehaviorRule("Commande de soin", BlockInstance("command", mapOf("name" to "soin", "description" to "Soigne le joueur")), emptyList(),
        listOf(BlockInstance("effect", mapOf("effect" to "instant_health", "seconds" to "1", "level" to "2")), BlockInstance("message", mapOf("text" to "Tu es soigné.")))),
)

@Composable
fun BehaviorEditorScreen(nav: androidx.navigation.NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var projectItems by remember { mutableStateOf(emptyList<String>()) }
    val rules = remember { mutableStateListOf<BehaviorRule>() }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var written by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Int?>(null) }
    var exampleMenu by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            meta = runCatching { app.store.load(id) }.getOrNull()
            projectItems = runCatching { ProjectIndex.usableItems(app.store.files(id)) }.getOrDefault(emptyList())
            val files = app.store.files(id)
            val list = runCatching {
                if (files.exists(BehaviorRulesFile.PATH)) BehaviorRulesFile.parse(files.readText(BehaviorRulesFile.PATH)) else emptyList()
            }.onFailure { app.errorLog.log("Lecture des comportements", it) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { rules.clear(); rules.addAll(list); loaded = true }
        }
    }
    BackHandler(enabled = editing != null) { editing = null }

    fun compileAndSave() {
        val m = meta ?: return
        busy = true; written = null
        scope.launch(Dispatchers.Main.immediate) {
            val snapshot = rules.toList()
            val result = BehaviorCompiler.compile(snapshot, m.namespace)
            errors = result.errors
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    app.store.files(id).let { f ->
                        if (!f.exists("editeur")) f.createFolder("editeur")
                        f.writeText(BehaviorRulesFile.PATH, BehaviorRulesFile.toJson(snapshot), "Éditeur de comportements")
                    }
                    result.content?.let { ContentInstaller.install(app.store, id, it, overwrite = true, reason = "Éditeur de comportements") }
                }
            }
            busy = false
            r.onSuccess { applied ->
                dirty = false
                if (applied != null) { written = applied.written; snackbar.showSnackbar(context.getString(R.string.logic_compiled)) }
                else snackbar.showSnackbar(context.getString(R.string.logic_saved_with_errors))
            }.onFailure { app.errorLog.log("Compilation des comportements", it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    val current = editing
    Scaffold(topBar = {
        BmsTopBar(
            if (current == null) stringResource(R.string.logic_title) else stringResource(R.string.logic_edit_rule),
            subtitle = meta?.name,
            onBack = { if (current != null) editing = null else nav.popBackStack() },
        )
    }) { padding ->
        val m = meta
        if (!loaded || m == null || busy) { LoadingBox(); return@Scaffold }
        if (current != null && current in rules.indices) {
            RuleEditor(rules[current], m.namespace, projectItems, Modifier.padding(padding)) { updated -> rules[current] = updated; dirty = true }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.logic_intro))
            written?.let { InfoCard(stringResource(R.string.mob_files_written) + "\n" + it.joinToString("\n") { f -> "• $f" }, color = BmsColors.Primary) }
            if (errors.isNotEmpty()) InfoCard(stringResource(R.string.logic_errors) + "\n" + errors.joinToString("\n") { "• $it" }, color = BmsColors.Error)
            if (rules.isEmpty()) EmptyState(stringResource(R.string.logic_empty))
            rules.forEachIndexed { i, rule ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.name.ifBlank { "Règle ${i + 1}" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(rule.enabled, { rules[i] = rule.copy(enabled = it); dirty = true })
                        }
                        Text(BehaviorCompiler.summary(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val problems = BehaviorCompiler.check(rule, m.namespace)
                        if (problems.isNotEmpty() && rule.enabled) Text(problems.first(), style = MaterialTheme.typography.bodySmall, color = BmsColors.Error)
                        Row {
                            androidx.compose.material3.TextButton(onClick = { editing = i }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.logic_edit)) }
                            androidx.compose.material3.TextButton(onClick = { toDelete = i }) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    rules.add(BehaviorRule("Règle ${rules.size + 1}", BlockInstance("player_join"))); dirty = true; editing = rules.lastIndex
                }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.logic_add_rule)) }
                Box {
                    OutlinedButton(onClick = { exampleMenu = true }) { Text(stringResource(R.string.logic_examples)) }
                    DropdownMenu(exampleMenu, { exampleMenu = false }) {
                        examples(m.namespace).forEach { ex ->
                            DropdownMenuItem(text = { Text(ex.name) }, onClick = { exampleMenu = false; rules.add(ex); dirty = true })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { compileAndSave() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.logic_compile)) }
            if (dirty) HelpText(stringResource(R.string.logic_unsaved))
            Spacer(Modifier.height(24.dp))
        }
    }
    toDelete?.let { i ->
        ConfirmDialog(stringResource(R.string.logic_delete_title), stringResource(R.string.logic_delete_body), confirmLabel = stringResource(R.string.delete), destructive = true,
            onConfirm = { if (i in rules.indices) rules.removeAt(i); dirty = true; toDelete = null }, onDismiss = { toDelete = null })
    }
}

@Composable
private fun RuleEditor(rule: BehaviorRule, ns: String, projectItems: List<String>, modifier: Modifier, onChange: (BehaviorRule) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(rule.name, { onChange(rule.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.logic_rule_name)) }, singleLine = true)
        InfoCard(BehaviorCompiler.summary(rule))
        val problems = BehaviorCompiler.check(rule, ns)
        if (problems.isNotEmpty()) InfoCard(problems.joinToString("\n"), color = BmsColors.Warning)

        CategoryHeader(BlockCategory.EVENT)
        BlockCard(rule.trigger, BlockCategory.EVENT, projectItems, ns, onChange = { onChange(rule.copy(trigger = it)) }, kindPicker = true)

        CategoryHeader(BlockCategory.CONDITION)
        if (rule.conditions.isEmpty()) HelpText(stringResource(R.string.logic_no_condition))
        rule.conditions.forEachIndexed { i, c ->
            BlockCard(c, BlockCategory.CONDITION, projectItems, ns,
                onChange = { u -> onChange(rule.copy(conditions = rule.conditions.toMutableList().also { it[i] = u })) },
                onRemove = { onChange(rule.copy(conditions = rule.conditions.toMutableList().also { it.removeAt(i) })) })
        }
        AddBlockButton(BlockCategory.CONDITION, stringResource(R.string.logic_add_condition)) { onChange(rule.copy(conditions = rule.conditions + it)) }

        CategoryHeader(BlockCategory.ACTION)
        rule.actions.forEachIndexed { i, a ->
            BlockCard(a, BlockCategory.ACTION, projectItems, ns,
                onChange = { u -> onChange(rule.copy(actions = rule.actions.toMutableList().also { it[i] = u })) },
                onRemove = { onChange(rule.copy(actions = rule.actions.toMutableList().also { it.removeAt(i) })) },
                onMove = { d ->
                    val j = i + d
                    if (j in rule.actions.indices) onChange(rule.copy(actions = rule.actions.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }))
                })
        }
        AddBlockButton(BlockCategory.ACTION, stringResource(R.string.logic_add_action)) { onChange(rule.copy(actions = rule.actions + it)) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CategoryHeader(c: BlockCategory) {
    Text(c.labelFr.uppercase(), style = MaterialTheme.typography.titleMedium, color = categoryColor(c), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AddBlockButton(category: BlockCategory, label: String, onAdd: (BlockInstance) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, border = BorderStroke(1.dp, categoryColor(category))) {
            Icon(Icons.Filled.Add, null, tint = categoryColor(category)); Spacer(Modifier.width(6.dp)); Text(label)
        }
        DropdownMenu(open, { open = false }) {
            BehaviorCatalog.of(category).forEach { def ->
                DropdownMenuItem(text = { Text(def.labelFr) }, onClick = { open = false; onAdd(BlockInstance(def.kind)) })
            }
        }
    }
}

@Composable
private fun BlockCard(
    inst: BlockInstance,
    category: BlockCategory,
    projectItems: List<String>,
    ns: String,
    onChange: (BlockInstance) -> Unit,
    onRemove: (() -> Unit)? = null,
    onMove: ((Int) -> Unit)? = null,
    kindPicker: Boolean = false,
) {
    val def: BlockDef? = BehaviorCatalog.byKind(inst.kind, category)
    val color = categoryColor(category)
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, color),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(def?.labelFr ?: "Bloc inconnu : ${inst.kind}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (onMove != null) {
                    IconButton(onClick = { onMove(-1) }) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.logic_up)) }
                    IconButton(onClick = { onMove(1) }) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.logic_down)) }
                }
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) }
            }
            if (kindPicker) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BehaviorCatalog.of(category).forEach { d ->
                        FilterChip(selected = d.kind == inst.kind, onClick = { if (d.kind != inst.kind) onChange(BlockInstance(d.kind)) }, label = { Text(d.labelFr) })
                    }
                }
            }
            if (def != null && def.helpFr.isNotBlank()) HelpText(def.helpFr)
            def?.params?.forEach { ps ->
                val value = inst[ps.key]
                fun set(v: String) = onChange(inst.copy(params = inst.params + (ps.key to v)))
                when (ps.type) {
                    ParamType.ITEM, ParamType.ENTITY, ParamType.BLOCK -> ItemField(ps.labelFr, value, ::set, projectItems)
                    ParamType.EFFECT -> {
                        Text(ps.labelFr)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BehaviorCatalog.EFFECTS.forEach { (eid, label) -> FilterChip(selected = value == eid, onClick = { set(eid) }, label = { Text(label) }) }
                        }
                    }
                    ParamType.DIMENSION -> {
                        OutlinedTextField(value, { set(it.trim()) }, Modifier.fillMaxWidth(), label = { Text(ps.labelFr) }, singleLine = true)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("minecraft:overworld" to "Overworld", "minecraft:nether" to "Nether", "minecraft:the_end" to "End").forEach { (d, l) ->
                                AssistChip(onClick = { set(d) }, label = { Text(l) })
                            }
                        }
                    }
                    ParamType.NUMBER -> OutlinedTextField(value, { set(it.trim()) }, Modifier.fillMaxWidth(), label = { Text(ps.labelFr) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    ParamType.COMMAND_NAME -> OutlinedTextField(value, { set(it.trim().lowercase()) }, Modifier.fillMaxWidth(), label = { Text(ps.labelFr) },
                        singleLine = true, supportingText = { Text("/$ns:$value") })
                    else -> OutlinedTextField(value, { set(it) }, Modifier.fillMaxWidth(), label = { Text(ps.labelFr) }, singleLine = ps.type != ParamType.TEXT)
                }
            }
        }
    }
}
