package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.OreDrop
import com.bedrockmodstudio.core.bedrock.OreGenerator
import com.bedrockmodstudio.core.bedrock.OreSpec
import com.bedrockmodstudio.core.bedrock.OreWorld
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.logic.BehaviorCompiler
import com.bedrockmodstudio.core.logic.BehaviorRule
import com.bedrockmodstudio.core.logic.BlockInstance
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OresAndMenusTest {

    @Test
    fun `minerai complet - bloc, gemme, cuisson, feature et feature rule`() {
        for (world in OreWorld.entries) for (drop in OreDrop.entries) {
            val store = ProjectStore(tempDir())
            val meta = store.create("Minerais ${world.name}")
            val spec = OreSpec("Saphir", shortId = "saphir", world = world, drop = drop, resultName = "Gemme de saphir",
                minY = world.minY + 5, maxY = world.minY + 40, veinSize = 6, veinsPerChunk = 3)
            assertEquals(emptyList(), spec.validate())
            val content = OreGenerator.generate(meta.namespace, spec, BedrockVersions.DEFAULT)
            ContentInstaller.install(store, meta.id, content, false, "t")
            val files = store.files(meta.id)
            val ns = meta.namespace
            assertTrue(files.exists("behavior_pack/blocks/saphir_ore.json"))
            assertTrue(files.exists("behavior_pack/items/gemme_de_saphir.json"))
            assertTrue(files.exists("behavior_pack/recipes/saphir_ore_cuisson.json"))
            assertEquals(drop == OreDrop.GEM, files.exists("behavior_pack/loot_tables/blocks/saphir_ore.json"))

            val feature = JsonParser.parseOrThrow(files.readText("behavior_pack/features/saphir_ore_feature.json")) as JsonObject
            assertEquals("1.13.0", feature.str("format_version"))
            val ore = feature.obj("minecraft:ore_feature")!!
            assertEquals("$ns:saphir_ore_feature", ore.obj("description")!!.str("identifier"))
            val rule0 = ore.arr("replace_rules")!!.items.single() as JsonObject
            assertEquals("$ns:saphir_ore", rule0.str("places_block"))
            assertEquals(world.replaces, rule0.arr("may_replace")!!.items.map { it.asString })

            val rule = (JsonParser.parseOrThrow(files.readText("behavior_pack/feature_rules/saphir_ore_rule.json")) as JsonObject).obj("minecraft:feature_rules")!!
            assertEquals("$ns:saphir_ore_rule", rule.obj("description")!!.str("identifier"))
            assertEquals("$ns:saphir_ore_feature", rule.obj("description")!!.str("places_feature"))
            assertEquals("underground_pass", rule.obj("conditions")!!.str("placement_pass"))
            val filter = rule.obj("conditions")!!.arr("minecraft:biome_filter")!!.items.single() as JsonObject
            assertEquals(world.biomeTag, filter.str("value"))
            val y = rule.obj("distribution")!!.obj("y")!!.arr("extent")!!.items.map { it.toString().toDouble().toInt() }
            assertEquals(listOf(world.minY + 5, world.minY + 40), y)

            val report = Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run()
            assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        }
    }

    @Test
    fun `minerai - erreurs lisibles`() {
        val bad = OreSpec("", world = OreWorld.NETHER, minY = -20, maxY = 500, veinSize = 0, resultName = "")
        val e = bad.validate()
        assertTrue(e.any { it.contains("nom affiché") })
        assertTrue(e.any { it.contains("filon") })
        assertTrue(e.any { it.contains("Nether") })
        assertTrue(e.any { it.contains("gemme") })
    }

    @Test
    fun `menus - formulaire, choix des boutons, dependance server-ui`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Menus")
        val rules = listOf(
            BehaviorRule("Ouvrir", BlockInstance("item_use", mapOf("item" to "minecraft:compass")), actions = listOf(
                BlockInstance("open_menu", mapOf("menu" to "boutique", "title" to "Boutique", "body" to "Que veux-tu ?", "buttons" to "Épée | Pain | Retour")),
            )),
            BehaviorRule("Épée", BlockInstance("menu_choice", mapOf("menu" to "boutique", "button" to "1")),
                actions = listOf(BlockInstance("give", mapOf("item" to "minecraft:iron_sword", "count" to "1")))),
            BehaviorRule("Pain", BlockInstance("menu_choice", mapOf("menu" to "boutique", "button" to "2")),
                actions = listOf(BlockInstance("give", mapOf("item" to "minecraft:bread", "count" to "3")))),
        )
        val r = BehaviorCompiler.compile(rules, meta.namespace)
        assertEquals(emptyList(), r.errors)
        assertTrue(r.content!!.needsServerUi)
        assertTrue(r.code.contains("import { ActionFormData } from \"@minecraft/server-ui\";"))
        assertTrue(r.code.contains("openMenu(player, \"boutique\", \"Boutique\", \"Que veux-tu ?\", [\"Épée\", \"Pain\", \"Retour\"]);"))
        assertTrue(r.code.contains("onMenuButton(\"boutique\", 2, rule3);"))
        ContentInstaller.install(store, meta.id, r.content!!, false, "t")
        val manifest = JsonParser.parseOrThrow(store.files(meta.id).readText("behavior_pack/manifest.json")) as JsonObject
        val deps = manifest.arr("dependencies")!!.items.map { it as JsonObject }
        assertEquals(BedrockVersions.DEFAULT.scriptServerUiVersion, deps.single { it.str("module_name") == "@minecraft/server-ui" }.str("version"))
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        ScriptsTest.exportForTypeCheck(store, meta.id, "menus")

        // Sans menu, pas de dépendance server-ui.
        val plain = BehaviorCompiler.compile(listOf(BehaviorRule("A", BlockInstance("player_join"), actions = listOf(BlockInstance("broadcast")))), "ns")
        assertTrue(!plain.content!!.needsServerUi && !plain.code.contains("server-ui"))
    }

    @Test
    fun `menus - bouton inexistant ou menu jamais ouvert`() {
        val open = BehaviorRule("Ouvrir", BlockInstance("player_join"), actions = listOf(BlockInstance("open_menu", mapOf("buttons" to "A | B"))))
        val r1 = BehaviorCompiler.compile(listOf(open, BehaviorRule("C", BlockInstance("menu_choice", mapOf("button" to "3")), actions = listOf(BlockInstance("broadcast")))), "ns")
        assertNull(r1.content)
        assertTrue(r1.errors.any { it.contains("n'a que 2 bouton") }, r1.errors.toString())
        val r2 = BehaviorCompiler.compile(listOf(BehaviorRule("C", BlockInstance("menu_choice", mapOf("menu" to "fantome")), actions = listOf(BlockInstance("broadcast")))), "ns")
        assertTrue(r2.errors.any { it.contains("n'ouvre le menu « fantome »") }, r2.errors.toString())
        val r3 = BehaviorCompiler.compile(listOf(BehaviorRule("V", BlockInstance("player_join"), actions = listOf(BlockInstance("open_menu", mapOf("buttons" to "A || B"))))), "ns")
        assertTrue(r3.errors.any { it.contains("bouton est vide") }, r3.errors.toString())
    }
}
