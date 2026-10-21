package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.nbt.Nbt
import com.bedrockmodstudio.core.nbt.NbtCodec
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.structure.BlockState
import com.bedrockmodstudio.core.structure.NaturalSpawn
import com.bedrockmodstudio.core.structure.StructureCodec
import com.bedrockmodstudio.core.structure.StructureGenerator
import com.bedrockmodstudio.core.structure.StructurePreset
import com.bedrockmodstudio.core.structure.StructureSpec
import com.bedrockmodstudio.core.structure.StructureTemplate
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructureTest {
    @Test
    fun `NBT petit-boutiste - en-tete exact et aller-retour de tous les types`() {
        val root = Nbt.compound(
            "format_version" to Nbt.Int(1),
            "b" to Nbt.Byte(-3), "s" to Nbt.Short(1234), "l" to Nbt.Long(1L shl 40), "f" to Nbt.Float(1.5f), "d" to Nbt.Double(-2.25),
            "ba" to Nbt.ByteArray(byteArrayOf(1, 2, 3)), "ia" to Nbt.IntArray(intArrayOf(7, -7)), "la" to Nbt.LongArray(longArrayOf(9L)),
            "txt" to Nbt.Str("Île €"), "vide" to Nbt.ListTag(10, emptyList()), "c" to Nbt.compound("x" to Nbt.Int(-1)),
        )
        val bytes = NbtCodec.write(root)
        // Racine composée (10), nom vide, puis entier (3) nommé « format_version » (longueur 14 en petit-boutiste) valant 1.
        assertContentEquals(byteArrayOf(10, 0, 0, 3, 14, 0), bytes.copyOfRange(0, 6))
        assertEquals("format_version", String(bytes, 6, 14))
        assertContentEquals(byteArrayOf(1, 0, 0, 0), bytes.copyOfRange(20, 24))
        val (name, back) = NbtCodec.read(bytes)
        assertEquals("", name)
        assertEquals(root, back)
        assertEquals(10, (back["vide"] as Nbt.ListTag).elementType) // type de liste vide conservé
    }

    @Test
    fun `fichiers endommages refuses sans plantage`() {
        val good = StructureCodec.write(StructurePreset.HOUSE.build())
        assertFailsWith<UserFacingException> { NbtCodec.read(good.copyOf(good.size / 2)) }
        assertFailsWith<UserFacingException> { NbtCodec.read(byteArrayOf(8, 0, 0)) }
        assertFailsWith<UserFacingException> { NbtCodec.read(byteArrayOf(10, 0, 0, 9, 1, 0, 'a'.code.toByte(), 3, -1, -1, -1, 127)) }
        assertFailsWith<UserFacingException> { StructureCodec.read(NbtCodec.write(Nbt.compound("size" to Nbt.intList(2, 2)))) }
    }

    @Test
    fun `ordre des blocs, vides, palette et coffre`() {
        val s = StructureTemplate(2, 3, 4)
        assertEquals(23, s.index(1, 2, 3))
        assertEquals((1 * 3 + 0) * 4 + 2, s.index(1, 0, 2))
        val stone = BlockState("minecraft:stone")
        s.set(1, 2, 3, stone); s.set(0, 0, 0, stone)
        s.setChest(1, 0, 2, "loot_tables/ns/t.json", facing = "north")
        assertEquals(2, s.palette.size) // la pierre n'est enregistrée qu'une fois
        val back = StructureCodec.read(StructureCodec.write(s))
        assertEquals(stone, back[1, 2, 3]); assertNull(back[0, 1, 0])
        assertEquals(-1, back.primary[back.index(0, 1, 0)])
        assertEquals("minecraft:chest", back[1, 0, 2]?.name)
        val data = (back.positionData[s.index(1, 0, 2).toString()] as Nbt.Compound)["block_entity_data"] as Nbt.Compound
        assertEquals(Nbt.Str("loot_tables/ns/t.json"), data["LootTable"])
        assertEquals(3, back.blockCount())
        s.set(1, 0, 2, null)
        assertTrue(s.positionData.map.isEmpty()) // retirer le coffre retire ses données
        val r = back.resized(1, 3, 4)
        assertEquals(stone, r[0, 0, 0]); assertEquals(1, r.sizeX)
    }

    @Test
    fun `modeles de depart lisibles et identifiants de chemin`() {
        for (p in StructurePreset.entries) {
            val s = p.build()
            val back = StructureCodec.read(StructureCodec.write(s))
            assertContentEquals(s.primary, back.primary, p.name)
            if (p != StructurePreset.EMPTY) assertTrue(s.blockCount() > 0, p.name)
        }
        assertEquals("bms_ab:maison", StructureGenerator.idForPath("behavior_pack/structures/bms_ab/maison.mcstructure"))
        assertEquals("mystructure:tour", StructureGenerator.idForPath("behavior_pack/structures/tour.mcstructure"))
        assertNull(StructureGenerator.idForPath("behavior_pack/entities/x.json"))
    }

    @Test
    fun `structure avec coffre et generation naturelle - installation et validation sans erreur`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Structures")
        val ns = meta.namespace
        val spec = StructureSpec("Salle au trésor", natural = NaturalSpawn(listOf("plains", "forest"), oneIn = 60, buried = true),
            chestLoot = listOf("minecraft:diamond" to 3, "minecraft:gold_ingot" to 8))
        val content = StructureGenerator.generate(ns, spec, StructurePreset.dungeon(null))
        ContentInstaller.install(store, meta.id, content, false, "t")
        val files = store.files(meta.id)
        val bytes = files.readBytes("behavior_pack/structures/$ns/salle_au_tresor.mcstructure")
        val s = StructureCodec.read(bytes)
        val chest = s.positionData.map.values.single() as Nbt.Compound
        assertEquals("loot_tables/$ns/salle_au_tresor_coffre.json", ((chest["block_entity_data"] as Nbt.Compound)["LootTable"] as Nbt.Str).v)
        val rule = JsonParser.parseOrThrow(files.readText("behavior_pack/feature_rules/salle_au_tresor_rule.json"))
        assertEquals("$ns:salle_au_tresor_feature", rule.path("minecraft:feature_rules", "description", "places_feature")?.asString)
        assertEquals("underground_pass", rule.path("minecraft:feature_rules", "conditions", "placement_pass")?.asString)
        val feature = JsonParser.parseOrThrow(files.readText("behavior_pack/features/salle_au_tresor_feature.json")) as JsonObject
        assertEquals("$ns:salle_au_tresor", feature.path("minecraft:structure_template_feature", "structure_name")?.asString)
        val loot = JsonParser.parseOrThrow(files.readText("behavior_pack/loot_tables/$ns/salle_au_tresor_coffre.json"))
        assertEquals("minecraft:diamond", loot.path("pools")?.let { (it as com.bedrockmodstudio.core.json.JsonArray)[0] }
            ?.path("entries")?.let { (it as com.bedrockmodstudio.core.json.JsonArray)[0] }?.path("name")?.asString)
        assertEquals(listOf("$ns:salle_au_tresor"), ProjectIndex.structures(files))
        val report = Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        assertTrue(report.issues.none { it.code.startsWith("STRUCTURE") || it.code.startsWith("FEATURE") }, report.issues.joinToString("\n"))

        // Références cassées détectées : table de butin supprimée, structure renommée.
        store.files(meta.id).moveToTrash("behavior_pack/loot_tables/$ns/salle_au_tresor_coffre.json")
        store.files(meta.id).rename("behavior_pack/structures/$ns/salle_au_tresor.mcstructure", "autre.mcstructure")
        val broken = Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run()
        assertTrue(broken.errors.any { it.code == "STRUCTURE_MISSING" }, broken.issues.joinToString("\n"))
        assertTrue(broken.errors.any { it.code == "LOOT_TABLE_MISSING" }, broken.issues.joinToString("\n"))
    }

    @Test
    fun `parametres invalides refuses`() {
        val errors = StructureSpec("", shortId = "Pas Bon", natural = NaturalSpawn(listOf("Plaine!"), oneIn = 0), chestLoot = listOf("x y" to 99)).validate()
        assertEquals(6, errors.size, errors.joinToString("\n"))
    }
}

class StructureMeshTest {
    @Test
    fun `apercu 3D - seules les faces exterieures sont dessinees`() {
        val s = StructureTemplate(3, 3, 3)
        s.fill(0, 0, 0, 2, 2, 2, BlockState("minecraft:stone"))
        val r = com.bedrockmodstudio.core.structure.StructureMesh.build(s)
        assertEquals(26, r.blockIndex.size) // le bloc central, invisible, est omis
        assertEquals(6 * 9, r.faces.size) // 9 faces visibles par côté du cube 3 × 3 × 3
        s.set(1, 1, 1, BlockState(StructureTemplate.AIR))
        assertEquals(6 * 9 + 6, com.bedrockmodstudio.core.structure.StructureMesh.build(s).faces.size) // cavité intérieure
    }
}
