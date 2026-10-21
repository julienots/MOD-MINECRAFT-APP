package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.BlockGenerator
import com.bedrockmodstudio.core.bedrock.BlockSpec
import com.bedrockmodstudio.core.bedrock.BlockStyle
import com.bedrockmodstudio.core.bedrock.ItemGenerator
import com.bedrockmodstudio.core.bedrock.ItemKind
import com.bedrockmodstudio.core.bedrock.ItemSpec
import com.bedrockmodstudio.core.bedrock.KnownComponents
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.bedrock.RecipeGenerator
import com.bedrockmodstudio.core.bedrock.RecipeSpec
import com.bedrockmodstudio.core.image.PixelArt
import com.bedrockmodstudio.core.image.PngInfo
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.packaging.ExportKind
import com.bedrockmodstudio.core.packaging.ProjectExporter
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.validation.Severity
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratorsTest {
    private val p = BedrockVersions.DEFAULT

    private fun validate(store: ProjectStore, id: String) = store.load(id).let {
        Validator(store.dirOf(id), it.namespace, BedrockVersions.byId(it.targetVersion)).run()
    }

    /** Aucun problème lié au contenu généré : ni erreur, ni référence manquante, ni composant inconnu. */
    private fun assertClean(store: ProjectStore, id: String) {
        val r = validate(store, id)
        assertEquals(emptyList(), r.errors, r.issues.joinToString("\n"))
        val noisy = r.issues.filter { it.code.endsWith("_MISSING") || it.code.startsWith("COMPONENT_") }
        assertEquals(emptyList(), noisy, noisy.joinToString("\n"))
    }

    @Test
    fun `creatures de tous temperaments sans composant inconnu`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        for (t in com.bedrockmodstudio.core.bedrock.MobTemperament.entries) {
            store.files(meta.id).apply(com.bedrockmodstudio.core.bedrock.MobGenerator.generate(meta.namespace,
                com.bedrockmodstudio.core.bedrock.MobSpec("Bête ${t.name}", shortId = "bete_${t.name.lowercase()}", temperament = t,
                    scale = 1.5, spawnNaturally = true), p), false, "t")
        }
        assertClean(store, meta.id)
    }

    @Test
    fun `gabarits de pixel art 16x16`() {
        listOf(PixelArt.SWORD, PixelArt.PICKAXE, PixelArt.AXE, PixelArt.SHOVEL, PixelArt.HELMET, PixelArt.CHESTPLATE,
            PixelArt.LEGGINGS, PixelArt.BOOTS, PixelArt.FOOD, PixelArt.GEM).forEach { t ->
            assertEquals(16, t.size); assertTrue(t.all { it.length == 16 })
        }
    }

    @Test
    fun `tous les types d'objets se generent sans erreur ni composant inconnu`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Arsenal")
        val files = store.files(meta.id)
        for (k in ItemKind.entries) {
            val spec = ItemSpec("Objet ${k.name}", shortId = "obj_${k.name.lowercase()}", kind = k, repairItem = "minecraft:iron_ingot")
            files.apply(ItemGenerator.generate(meta.namespace, spec, p), overwrite = false, reason = "test")
        }
        assertClean(store, meta.id)
        val atlas = JsonParser.parseOrThrow(files.readText("resource_pack/textures/item_texture.json")) as JsonObject
        assertEquals(ItemKind.entries.size, atlas.obj("texture_data")!!.size)
        assertTrue(files.exists("resource_pack/attachables/obj_chestplate.player.json"))
        assertEquals(PngInfo.Size(64, 32), PngInfo.read(files.readBytes("resource_pack/textures/models/armor/arsenal_obj_chestplate.png")))
        assertEquals(PngInfo.Size(16, 16), PngInfo.read(files.readBytes("resource_pack/textures/items/obj_sword.png")))
        val sword = JsonParser.parseOrThrow(files.readText("behavior_pack/items/obj_sword.json"))
        val comps = sword.path("minecraft:item", "components") as JsonObject
        assertEquals(1, comps["minecraft:max_stack_size"]?.asInt)
        assertTrue(comps.keys.all { it in KnownComponents.ITEM })
        assertEquals("Objet SWORD", LangFile(files.readText("resource_pack/texts/fr_FR.lang"))["item.arsenal:obj_sword.name"])
    }

    @Test
    fun `blocs - atlas et blocks_json fusionnes sans perte`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Blocs")
        val files = store.files(meta.id)
        for (style in BlockStyle.entries) {
            val spec = BlockSpec("Bloc ${style.name}", shortId = "b_${style.name.lowercase()}", style = style,
                lightEmission = if (style == BlockStyle.LAMP) 15 else 0, dropItem = if (style == BlockStyle.ORE) "minecraft:diamond" else null)
            files.apply(BlockGenerator.generate(meta.namespace, spec, p), overwrite = false, reason = "test")
        }
        assertClean(store, meta.id)
        val terrain = JsonParser.parseOrThrow(files.readText("resource_pack/textures/terrain_texture.json")) as JsonObject
        assertEquals(BlockStyle.entries.size, terrain.obj("texture_data")!!.size)
        val blocksJson = JsonParser.parseOrThrow(files.readText("resource_pack/blocks.json")) as JsonObject
        assertEquals("stone", blocksJson.obj("blocs:b_stone")?.str("sound"))
        assertTrue(files.exists("behavior_pack/loot_tables/blocks/b_ore.json"))
        val lamp = JsonParser.parseOrThrow(files.readText("behavior_pack/blocks/b_lamp.json"))
        assertEquals(15, lamp.path("minecraft:block", "components", "minecraft:light_emission")?.asInt)
        assertEquals("Bloc STONE", LangFile(files.readText("resource_pack/texts/en_US.lang"))["tile.blocs:b_stone.name"])
    }

    @Test
    fun `fichier partage casse - rien n'est ecrit`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Casse")
        val files = store.files(meta.id)
        files.createFile("resource_pack/textures/terrain_texture.json", "{ cassé".toByteArray())
        assertFailsWith<UserFacingException> {
            files.apply(BlockGenerator.generate(meta.namespace, BlockSpec("Pierre bleue"), p), overwrite = false, reason = "test")
        }
        assertFalse(files.exists("behavior_pack/blocks/pierre_bleue.json"))
        assertEquals("{ cassé", files.readText("resource_pack/textures/terrain_texture.json"))
    }

    @Test
    fun `une entree existante d'atlas n'est pas ecrasee sans permission`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Atlas")
        val files = store.files(meta.id)
        files.createFile("resource_pack/textures/item_texture.json",
            """{"resource_pack_name": "x", "texture_name": "atlas.items", "texture_data": {"atlas_rubis": {"textures": "textures/items/perso"}, "autre": {"textures": "textures/items/autre"}}}""".toByteArray())
        val r = files.apply(ItemGenerator.generate(meta.namespace, ItemSpec("Rubis"), p), overwrite = false, reason = "test")
        assertTrue(r.skippedExisting.any { it.endsWith("atlas_rubis") })
        val atlas = JsonParser.parseOrThrow(files.readText("resource_pack/textures/item_texture.json")) as JsonObject
        assertEquals("textures/items/perso", atlas.obj("texture_data")!!.obj("atlas_rubis")!!.str("textures"))
        assertTrue(atlas.obj("texture_data")!!.containsKey("autre"))
    }

    @Test
    fun `recettes - motif compacte, references verifiees`() {
        val (pattern, key) = RecipeGenerator.compact(listOf(null, "minecraft:diamond", null, null, "minecraft:diamond", null, null, "minecraft:stick", null))
        assertEquals(listOf("A", "A", "B"), pattern)
        assertEquals(mapOf("A" to "minecraft:diamond", "B" to "minecraft:stick"), key)

        val store = ProjectStore(tempDir())
        val meta = store.create("Forge")
        val files = store.files(meta.id)
        val ns = meta.namespace
        files.apply(ItemGenerator.generate(ns, ItemSpec("Lingot de rubis", shortId = "rubis", kind = ItemKind.SIMPLE), p), false, "t")
        files.apply(ItemGenerator.generate(ns, ItemSpec("Épée de rubis", shortId = "epee_rubis", kind = ItemKind.SWORD), p), false, "t")
        files.apply(RecipeGenerator.generate(ns, RecipeGenerator.standardFor(ItemKind.SWORD, "$ns:rubis", "$ns:epee_rubis", "epee_rubis")!!, p), false, "t")
        files.apply(RecipeGenerator.generate(ns, RecipeSpec.Furnace("rubis_four", "minecraft:redstone", "$ns:rubis"), p), false, "t")
        files.apply(RecipeGenerator.generate(ns, RecipeSpec.Shapeless("rubis_x9", listOf("$ns:rubis"), "minecraft:emerald", 2), p), false, "t")
        assertClean(store, meta.id)
        val r = JsonParser.parseOrThrow(files.readText("behavior_pack/recipes/epee_rubis.json"))
        assertEquals("[\"A\", \"A\", \"B\"]", r.path("minecraft:recipe_shaped", "pattern").toString())
        assertEquals("$ns:rubis", (r.path("minecraft:recipe_shaped", "unlock") as com.bedrockmodstudio.core.json.JsonArray)[0].path("item")?.asString)

        files.apply(RecipeGenerator.generate(ns, RecipeSpec.Shapeless("fantome", listOf("$ns:inexistant"), "minecraft:stick"), p), false, "t")
        assertTrue(validate(store, meta.id).errors.any { it.code == "RECIPE_UNKNOWN_ITEM" })
        assertTrue(RecipeSpec.Shaped("x", List(9) { null }, "minecraft:stick").validate().isNotEmpty())
    }

    @Test
    fun `faute de frappe dans un composant detectee`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val f = store.files(meta.id)
        val path = "behavior_pack/entities/sanglier_mousses.json"
        f.writeText(path, f.readText(path).replace("\"minecraft:health\"", "\"minecraft:helth\""))
        val typo = validate(store, meta.id).issues.single { it.code == "COMPONENT_TYPO" }
        assertTrue(typo.message.contains("minecraft:health"))
        assertEquals(Severity.WARNING, typo.severity)
    }

    @Test
    fun `export complet avec objets, armures, blocs et recettes`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val f = store.files(meta.id)
        val ns = meta.namespace
        f.apply(ItemGenerator.generate(ns, ItemSpec("Casque de mousse", kind = ItemKind.HELMET), p), false, "t")
        f.apply(BlockGenerator.generate(ns, BlockSpec("Minerai de mousse", style = BlockStyle.ORE), p), false, "t")
        val out = ok(ProjectExporter(store).export(meta.id, ExportKind.MCADDON, tempDir()))
        val entries = zipEntries(out.file)
        assertTrue(entries.any { it.endsWith("_RP/attachables/casque_de_mousse.player.json") })
        assertTrue(entries.any { it.endsWith("_RP/blocks.json") })
        assertTrue(entries.any { it.endsWith("_BP/blocks/minerai_de_mousse.json") })
    }
}
