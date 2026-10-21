package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.logic.BehaviorCatalog
import com.bedrockmodstudio.core.logic.BehaviorCompiler
import com.bedrockmodstudio.core.logic.BehaviorRule
import com.bedrockmodstudio.core.logic.BehaviorRulesFile
import com.bedrockmodstudio.core.logic.BlockCategory
import com.bedrockmodstudio.core.logic.BlockInstance
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BehaviorRulesTest {
    /** Une règle par événement, et toutes les conditions et actions du catalogue utilisées au moins une fois. */
    private fun everything(): List<BehaviorRule> {
        val allConditions = BehaviorCatalog.of(BlockCategory.CONDITION).map { BlockInstance(it.kind) }
        val allActions = BehaviorCatalog.of(BlockCategory.ACTION).map { BlockInstance(it.kind) }
        return BehaviorCatalog.of(BlockCategory.EVENT).mapIndexed { i, ev ->
            val trigger = if (ev.kind == "command") BlockInstance("command", mapOf("name" to "regle$i")) else BlockInstance(ev.kind)
            BehaviorRule("Règle ${ev.labelFr}", trigger, allConditions, allActions)
        }
    }

    @Test
    fun `toutes les regles se compilent, s'installent et passent la validation`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Logique")
        val result = BehaviorCompiler.compile(everything(), meta.namespace)
        assertEquals(emptyList(), result.errors)
        ContentInstaller.install(store, meta.id, result.content!!, false, "t")
        val code = store.files(meta.id).readText("behavior_pack/scripts/bms/comportements.js")
        assertTrue(code.contains("system.runTimeout")) // l'action « attendre »
        assertTrue(code.contains("registerCommand"))
        assertTrue(store.files(meta.id).readText("behavior_pack/scripts/main.js").contains("comportements.js"))
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        ScriptsTest.exportForTypeCheck(store, meta.id, "comportements")
    }

    @Test
    fun `erreurs explicites, rien n'est produit`() {
        val bad = listOf(
            BehaviorRule("Sans action", BlockInstance("player_join")),
            BehaviorRule("Bloc inconnu", BlockInstance("player_join"), actions = listOf(BlockInstance("voler_dans_le_ciel"))),
            BehaviorRule("Nombre", BlockInstance("timer", mapOf("seconds" to "zéro")), actions = listOf(BlockInstance("broadcast"))),
            BehaviorRule("Objet", BlockInstance("item_use", mapOf("item" to "Pas Valide")), actions = listOf(BlockInstance("broadcast"))),
        )
        val r = BehaviorCompiler.compile(bad, "ns")
        assertNull(r.content)
        assertTrue(r.errors.any { it.contains("au moins une action") })
        assertTrue(r.errors.any { it.contains("ne peut pas être traduit") })
        assertTrue(r.errors.any { it.contains("nombre attendu") })
        assertTrue(r.errors.any { it.contains("identifiant invalide") })
        val dup = List(2) { BehaviorRule("C$it", BlockInstance("command", mapOf("name" to "x")), actions = listOf(BlockInstance("broadcast"))) }
        assertTrue(BehaviorCompiler.compile(dup, "ns").errors.any { it.contains("plusieurs fois") })
    }

    @Test
    fun `regle desactivee ignoree, resume lisible, aller-retour JSON`() {
        val rules = listOf(
            BehaviorRule("Baguette", BlockInstance("item_use", mapOf("item" to "ns:baguette")),
                listOf(BlockInstance("chance", mapOf("percent" to "50"))),
                listOf(BlockInstance("effect", mapOf("effect" to "speed", "seconds" to "5", "level" to "2")), BlockInstance("wait", mapOf("seconds" to "1")),
                    BlockInstance("message", mapOf("text" to "Hop !")))),
            BehaviorRule("Brouillon", BlockInstance("player_join"), enabled = false),
        )
        assertEquals("Quand un joueur utilise ns:baguette, si avec 50 % de chance, alors : effet speed niveau 2 pendant 5 s, puis attendre 1 s, puis écrire « Hop ! » au joueur.",
            BehaviorCompiler.summary(rules[0]))
        assertEquals(emptyList(), BehaviorCompiler.compile(rules, "ns").errors) // la règle désactivée (sans action) est ignorée
        assertEquals(rules, BehaviorRulesFile.parse(BehaviorRulesFile.toJson(rules)))
    }
}
