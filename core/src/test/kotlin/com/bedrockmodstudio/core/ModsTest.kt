package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.mods.ModCatalog
import com.bedrockmodstudio.core.mods.ModCatalog.Kind
import com.bedrockmodstudio.core.mods.PackMerger
import com.bedrockmodstudio.core.mods.QuickEdit
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModsTest {

    @Test
    fun `catalogue du projet d'exemple - creature avec modele, texture et reglages`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val cat = ModCatalog.scan(store.files(meta.id))
        val mob = cat.of(Kind.ENTITY).single()
        assertEquals("exemple_bms:sanglier_mousses", mob.id)
        assertEquals("Sanglier des mousses", mob.name)
        assertNotNull(mob.geometryPath, "modèle trouvé")
        assertNotNull(mob.texturePath, "texture trouvée")
        assertEquals(24.0, mob.stats[QuickEdit.Stat.HEALTH])
        assertTrue(mob.animations.isNotEmpty(), "animations déclarées")
        assertTrue(mob.problems.isEmpty(), mob.problems.toString())
        assertTrue(cat.of(Kind.ITEM).isNotEmpty())
        val block = cat.of(Kind.BLOCK).first()
        assertNotNull(block.blockFaces?.any, "texture du bloc trouvée")
    }

    @Test
    fun `reglage, nom, variante et suppression`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val files = store.files(meta.id)
        var cat = ModCatalog.scan(files)
        val mob = cat.of(Kind.ENTITY).single()

        QuickEdit.setStat(files, mob, QuickEdit.Stat.HEALTH, 80.0)
        QuickEdit.setStat(files, mob, QuickEdit.Stat.SCALE, 2.5)
        QuickEdit.setStat(files, mob, QuickEdit.Stat.HEALTH, 99999.0) // borné
        val root = JsonParser.parseOrThrow(files.readText(mob.behaviorPath!!))
        assertEquals(1000, root.path("minecraft:entity", "components", "minecraft:health", "value")?.asInt)
        assertEquals(1000, root.path("minecraft:entity", "components", "minecraft:health", "max")?.asInt)
        assertEquals(2.5, root.path("minecraft:entity", "components", "minecraft:scale", "value")?.asDouble)
        assertTrue(files.history(mob.behaviorPath!!).isNotEmpty(), "ancienne version gardée")

        QuickEdit.rename(files, mob, "Sanglier géant")
        assertEquals("Sanglier géant", ModCatalog.scan(files).byId(mob.id)!!.name)
        assertFailsWith<UserFacingException> { QuickEdit.rename(files, mob, "  ") }

        cat = ModCatalog.scan(files)
        val newId = QuickEdit.duplicate(files, cat, cat.byId(mob.id)!!, "Sanglier de feu")
        cat = ModCatalog.scan(files)
        val copy = cat.byId(newId, Kind.ENTITY)!!
        assertEquals("Sanglier de feu", copy.name)
        assertNotNull(copy.clientPath)
        assertEquals(cat.byId(mob.id)!!.geometryId, copy.geometryId)
        assertTrue(PackMerger(store, tempDir()).duplicateIds(files).isEmpty())

        val removed = QuickEdit.delete(files, copy)
        assertEquals(2, removed.size)
        assertEquals(null, ModCatalog.scan(files).byId(newId))
    }

    @Test
    fun `objet - pile maximale au format nombre ou objet`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val files = store.files(meta.id)
        val item = ModCatalog.scan(files).of(Kind.ITEM).first()
        QuickEdit.setStat(files, item, QuickEdit.Stat.MAX_STACK, 16.0)
        assertEquals(16.0, ModCatalog.scan(files).byId(item.id)!!.stats[QuickEdit.Stat.MAX_STACK])
    }

    private fun addon(ns: String, withScript: Boolean): ByteArray {
        val bpManifest = """{"format_version":2,"header":{"name":"Mod Dragons","uuid":"11111111-1111-4111-8111-111111111111","version":[1,0,0],"min_engine_version":[1,21,0]},
            "modules":[{"type":"data","uuid":"22222222-2222-4222-8222-222222222222","version":[1,0,0]}${
            if (withScript) ""","{"type":"script","language":"javascript","uuid":"55555555-5555-4555-8555-555555555555","version":[1,0,0],"entry":"scripts/index.js"}""" else ""}],
            "dependencies":[${if (withScript) """{"module_name":"@minecraft/server","version":"2.1.0-beta"}""" else ""}]}"""
        val rpManifest = """{"format_version":2,"header":{"name":"Mod Dragons RP","uuid":"33333333-3333-4333-8333-333333333333","version":[1,0,0],"min_engine_version":[1,21,0]},
            "modules":[{"type":"resources","uuid":"44444444-4444-4444-8444-444444444444","version":[1,0,0]}]}"""
        val entity = """{"format_version":"1.21.0","minecraft:entity":{"description":{"identifier":"$ns:dragon","is_spawnable":true,"is_summonable":true},
            "components":{"minecraft:health":{"value":200,"max":200},"minecraft:movement":{"value":0.3}}}}"""
        val client = """{"format_version":"1.10.0","minecraft:client_entity":{"description":{"identifier":"$ns:dragon",
            "textures":{"default":"textures/entity/dragon"},"geometry":{"default":"geometry.dragon"},"animations":{"walk":"animation.dragon.walk"}}}}"""
        val geo = """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.dragon","texture_width":64,"texture_height":64},
            "bones":[{"name":"body","pivot":[0,0,0],"cubes":[{"origin":[-4,0,-8],"size":[8,8,16],"uv":[0,0]}]}]}]}"""
        val atlas = """{"resource_pack_name":"x","texture_name":"atlas.items","texture_data":{"dragon_scale":{"textures":"textures/items/dragon_scale"}}}"""
        val entries = mutableListOf(
            "Dragons_BP/manifest.json" to bpManifest.toByteArray(),
            "Dragons_BP/entities/dragon.json" to entity.toByteArray(),
            "Dragons_RP/manifest.json" to rpManifest.toByteArray(),
            "Dragons_RP/entity/dragon.json" to client.toByteArray(),
            "Dragons_RP/models/entity/dragon.geo.json" to geo.toByteArray(),
            "Dragons_RP/textures/item_texture.json" to atlas.toByteArray(),
            "Dragons_RP/texts/en_US.lang" to "entity.$ns:dragon.name=Dragon\npack.name=Dragons\n".toByteArray(),
            "Dragons_RP/textures/entity/dragon.png" to com.bedrockmodstudio.core.image.PixelImage(64, 64).toPng(),
        )
        if (withScript) entries += "Dragons_BP/scripts/index.js" to "import { world } from \"@minecraft/server\";\nimport \"./util.js\";\n".toByteArray()
        if (withScript) entries += "Dragons_BP/scripts/util.js" to "export const x = 1;\n".toByteArray()
        return zipOf(*entries.toTypedArray())
    }

    @Test
    fun `ajouter un mod existant dans un projet - fusion, traductions, atlas et scripts`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val files = store.files(meta.id)
        val itemsBefore = (JsonParser.parseOrThrow(files.readText("resource_pack/textures/item_texture.json")) as JsonObject)
            .obj("texture_data")!!.entries.size

        val r = PackMerger(store, tempDir()).merge(meta.id, addon("dragons", withScript = true).inputStream(), "dragons.mcaddon", PackMerger.Policy.KEEP_MINE)
        assertTrue(r.newContent.any { it.contains("Dragon") }, r.toString())
        assertTrue(r.added >= 4, r.toString())

        val cat = ModCatalog.scan(files)
        val dragon = cat.byId("dragons:dragon", Kind.ENTITY)!!
        assertEquals("Dragon", dragon.name)
        assertNotNull(dragon.geometryPath); assertNotNull(dragon.texturePath)
        assertEquals(200.0, dragon.stats[QuickEdit.Stat.HEALTH])

        // L'atlas est fusionné (les textures du projet restent).
        val atlas = (JsonParser.parseOrThrow(files.readText("resource_pack/textures/item_texture.json")) as JsonObject).obj("texture_data")!!
        assertEquals(itemsBefore + 1, atlas.entries.size)
        // Le nom du pack du mod n'écrase pas celui du projet.
        assertTrue(!files.readText("resource_pack/texts/en_US.lang").contains("pack.name=Dragons"))
        // Scripts rangés à part et importés depuis le point d'entrée du projet.
        assertTrue(files.exists("behavior_pack/scripts/mods/mod_dragons/util.js"))
        val entry = files.readText("behavior_pack/scripts/main.js")
        assertTrue(entry.contains("mods/mod_dragons/index.js"), entry)
        assertTrue(r.notes.any { it.contains("bêta") }, r.notes.toString())

        // Deuxième ajout du même mod : tout est identique, rien n'est dupliqué.
        val r2 = PackMerger(store, tempDir()).merge(meta.id, addon("dragons", withScript = true).inputStream(), "dragons.mcaddon", PackMerger.Policy.KEEP_MINE)
        assertEquals(0, r2.added); assertTrue(r2.identical > 0)
        assertTrue(r2.newContent.isEmpty())
    }

    @Test
    fun `conflit - garder mes fichiers ou prendre ceux du mod`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val files = store.files(meta.id)
        PackMerger(store, tempDir()).merge(meta.id, addon("dragons", false).inputStream(), "a.mcaddon", PackMerger.Policy.KEEP_MINE)
        val dragon = ModCatalog.scan(files).byId("dragons:dragon", Kind.ENTITY)!!
        QuickEdit.setStat(files, dragon, QuickEdit.Stat.HEALTH, 5.0)

        val keep = PackMerger(store, tempDir()).merge(meta.id, addon("dragons", false).inputStream(), "a.mcaddon", PackMerger.Policy.KEEP_MINE)
        assertTrue(keep.keptMine.isNotEmpty())
        assertEquals(5.0, ModCatalog.scan(files).byId("dragons:dragon")!!.stats[QuickEdit.Stat.HEALTH])

        val take = PackMerger(store, tempDir()).merge(meta.id, addon("dragons", false).inputStream(), "a.mcaddon", PackMerger.Policy.TAKE_THEIRS)
        assertTrue(take.replaced > 0)
        assertEquals(200.0, ModCatalog.scan(files).byId("dragons:dragon")!!.stats[QuickEdit.Stat.HEALTH])
    }

    @Test
    fun `refus des mods Java`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        assertFailsWith<UserFacingException> { PackMerger(store, tempDir()).merge(meta.id, ByteArray(4).inputStream(), "mod.jar", PackMerger.Policy.KEEP_MINE) }
    }
}
