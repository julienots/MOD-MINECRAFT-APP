package com.bedrockmodstudio.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.ai.AiAssistant
import com.bedrockmodstudio.ai.ApiKeyVault
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.assistant.OfflinePlanner
import com.bedrockmodstudio.core.assistant.Plan
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AssistantScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val vault = remember { ApiKeyVault(context.applicationContext) }
    var request by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var keep by remember { mutableStateOf(emptySet<Int>()) }
    var source by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { hasKey = withContext(Dispatchers.IO) { vault.hasKey() } }
    val (ctl, start) = rememberGenerator(id, "Assistant")

    fun show(p: Plan, from: String) { plan = p; keep = p.steps.indices.toSet(); source = from; aiError = null; ctl.written = null }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.assistant_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.assistant_intro))
            OutlinedTextField(request, { request = it.take(2000) }, Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("assistant_request"),
                label = { Text(stringResource(R.string.assistant_request)) }, placeholder = { Text(stringResource(R.string.assistant_example)) })
            Button(onClick = { show(OfflinePlanner.plan(request), context.getString(R.string.assistant_source_offline)) }, enabled = request.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assistant_analyze)) }
            if (hasKey) OutlinedButton(
                onClick = {
                    thinking = true; aiError = null
                    scope.launch(Dispatchers.Main.immediate) {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { AiAssistant.plan(vault.load() ?: error("Clé illisible : enregistre-la à nouveau."), request) }
                        }
                        thinking = false
                        r.onSuccess { show(it, context.getString(R.string.assistant_source_ai)) }
                            .onFailure { aiError = it.message; app.errorLog.log("Assistant IA", it) }
                    }
                },
                enabled = request.isNotBlank() && !thinking, modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (thinking) R.string.assistant_ai_running else R.string.assistant_ask_ai)) }
            aiError?.let { InfoCard(it, color = BmsColors.Warning) }

            plan?.let { p ->
                SectionTitle(stringResource(R.string.assistant_plan, source))
                p.notes.forEach { InfoCard(it, color = BmsColors.Warning) }
                p.steps.forEachIndexed { i, step ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i in keep, { keep = if (it) keep + i else keep - i })
                            Text(step.summaryFr(), Modifier.weight(1f))
                        }
                    }
                }
                if (p.steps.isNotEmpty()) {
                    HelpText(stringResource(R.string.assistant_review))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.Main.immediate) {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val meta = app.store.load(id)
                                        Plan(p.steps.filterIndexed { i, _ -> i in keep }, emptyList()).generate(meta.namespace, BedrockVersions.byId(meta.targetVersion) ?: BedrockVersions.DEFAULT)
                                    }
                                }
                                r.onSuccess { start(it) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                            }
                        },
                        enabled = keep.isNotEmpty() && !ctl.busy, modifier = Modifier.fillMaxWidth().testTag("assistant_apply"),
                    ) { Text(stringResource(R.string.assistant_apply, keep.size)) }
                }
                GenerationResult(ctl, stringResource(R.string.assistant_after))
            }

            SectionTitle(stringResource(R.string.assistant_ai_title))
            HelpText(stringResource(R.string.assistant_ai_help))
            if (hasKey) {
                Text(stringResource(R.string.assistant_key_saved))
                TextButton(onClick = { vault.delete(); hasKey = false }) { Text(stringResource(R.string.assistant_key_delete)) }
            } else if (!showKey) {
                OutlinedButton(onClick = { showKey = true }) { Text(stringResource(R.string.assistant_key_add)) }
            } else {
                OutlinedTextField(keyInput, { keyInput = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.assistant_key_label)) }, visualTransformation = PasswordVisualTransformation())
                Button(onClick = {
                    scope.launch(Dispatchers.Main.immediate) {
                        val r = withContext(Dispatchers.IO) { runCatching { vault.save(keyInput) } }
                        r.onSuccess { hasKey = true; showKey = false; keyInput = "" }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                    }
                }, enabled = keyInput.length >= 20) { Text(stringResource(R.string.assistant_key_save)) }
            }
        }
    }
}
