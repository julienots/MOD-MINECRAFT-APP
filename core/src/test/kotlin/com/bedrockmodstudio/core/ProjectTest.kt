package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.image.PngInfo
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.validation.Severity
import com.bedrockmodstudio.core.validation.Validator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectTest {

    private fun validate(store: ProjectStore, id: String) = store.load(id).let {
        Validator(store.dirOf(id), it.namespace, BedrockVersions.byId(it.targetVersion)).run()
    }

    @Test
    fun `nouveau projet - manifestes lies, UUID uniques, aucune erreur`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Mon Super Mod !", description = "Test")
        assertEquals("mon_super_mod", meta.namespace)
        val files = store.files(meta.id)
        val bp = JsonParser.parseOrThrow(files.readText("behavior_pack/manifest.json")) as JsonObject
        val rp = JsonParser.parseOrThrow(files.readText("resource_pack/manifest.json")) as JsonObject
        assertEquals(2, bp["format_version"]?.asInt)
        assertEquals(listOf(1, 26, 50), (bp.path("header", "min_engine_version") as JsonArray).items.map { it.asInt })
        val dep = (bp.arr("dependencies")!![0] as JsonObject)
        assertEquals(rp.path("header", "uuid")?.asString, dep.str("uuid"))
        val uuids = listOf(bp.path("header", "uuid"), (bp.arr("modules")!![0] as JsonObject)["uuid"],
            rp.path("header", "uuid"), (rp.arr("modules")!![0] as JsonObject)["uuid"]).map { it!!.asString!! }
        assertEquals(4, uuids.toSet().size)
        assertTrue(uuids.all { Identifiers.isValidUuid(it) })
        assertNotNull(PngInfo.read(files.readBytes("behavior_pack/pack_icon.png")))

        val report = validate(store, meta.id)
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
    }

    @Test
    fun `projet d'exemple complet sans erreur ni avertissement de reference`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val report = validate(store, meta.id)
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        assertTrue(report.warnings.none { it.code.endsWith("_MISSING") }, report.warnings.joinToString("\n"))
        val files = store.files(meta.id)
        val lang = LangFile(files.readText("resource_pack/texts/fr_FR.lang"))
        assertEquals("Sanglier des mousses", lang["entity.exemple_bms:sanglier_mousses.name"])
        val png = PngInfo.read(files.readBytes("resource_pack/textures/entity/sanglier_mousses.png"))
        assertEquals(PngInfo.Size(64, 64), png)
    }

    @Test
    fun `chaque temperament genere une entite coherente`() {
        for (t in MobTemperament.entries) {
            val c = MobGenerator.generate("ns", MobSpec("Bête", temperament = t), BedrockVersions.V1_26_30)
            val e = JsonParser.parseOrThrow(String(c.files["behavior_pack/entities/bete.json"]!!))
            val comps = e.path("minecraft:entity", "components") as JsonObject
            assertEquals(t != MobTemperament.PASSIVE, comps.containsKey("minecraft:attack"), t.name)
            assertEquals(t == MobTemperament.HOSTILE, comps.containsKey("minecraft:behavior.nearest_attackable_target"), t.name)
            assertEquals("1.26.20", e.path("format_version")?.asString)
        }
        assertTrue(MobSpec("x", health = 0).validate().isNotEmpty())
        assertTrue(MobSpec("x", lootItem = "pas valide").validate().isNotEmpty())
    }

    @Test
    fun `historique - modification puis restauration`() {
        val store = ProjectStore(tempDir())
        val id = store.create("Hist").id
        val f = store.files(id)
        f.createFile("behavior_pack/functions/a.mcfunction", "say v1".toByteArray())
        f.writeText("behavior_pack/functions/a.mcfunction", "say v2")
        f.writeText("behavior_pack/functions/a.mcfunction", "say v3")
        val h = f.history("behavior_pack/functions/a.mcfunction")
        assertEquals(2, h.size)
        f.restoreHistory("behavior_pack/functions/a.mcfunction", h.last().id)
        assertEquals("say v1", f.readText("behavior_pack/functions/a.mcfunction"))
        // La version v3 a elle-même été sauvegardée avant la restauration.
        assertEquals(3, f.history("behavior_pack/functions/a.mcfunction").size)
    }

    @Test
    fun `suppression = corbeille, restauration possible, jamais d'ecrasement`() {
        val store = ProjectStore(tempDir())
        val id = store.create("Trash").id
        val f = store.files(id)
        f.createFile("resource_pack/a.txt", "1".toByteArray())
        val t = f.moveToTrash("resource_pack/a.txt")
        assertFalse(f.exists("resource_pack/a.txt"))
        f.createFile("resource_pack/a.txt", "2".toByteArray())
        val restored = f.restoreFromTrash(t.id)
        assertNotEquals("resource_pack/a.txt", restored)
        assertEquals("2", f.readText("resource_pack/a.txt"))
        assertEquals("1", f.readText(restored))
        assertFailsWith<UserFacingException> { f.createFile("resource_pack/a.txt") }
    }

    @Test
    fun `renommer, dupliquer, importer un fichier`() {
        val store = ProjectStore(tempDir())
        val f = store.files(store.create("Ops").id)
        f.createFile("resource_pack/x.json", "{}".toByteArray())
        assertEquals("resource_pack/x_copie.json", f.duplicate("resource_pack/x.json"))
        assertEquals("resource_pack/x_copie2.json", f.duplicate("resource_pack/x.json"))
        assertEquals("resource_pack/y.json", f.rename("resource_pack/x.json", "y.json"))
        assertFailsWith<UserFacingException> { f.rename("resource_pack/y.json", "x_copie.json") }
        assertFailsWith<UserFacingException> { f.rename("resource_pack/y.json", "a/b.json") }
        assertEquals("resource_pack/y (2).json", f.importFile("resource_pack", "y.json", "{}".toByteArray()))
    }

    @Test
    fun `chemins hors du projet refuses`() {
        val store = ProjectStore(tempDir())
        val f = store.files(store.create("Secu").id)
        assertFailsWith<UserFacingException> { f.readText("../../etc/passwd") }
        assertFailsWith<UserFacingException> { f.writeText("../evil.json", "{}") }
        assertFailsWith<UserFacingException> { f.writeText(".history/x", "{}") }
        assertFailsWith<IllegalArgumentException> { store.dirOf("../x") }
    }

    @Test
    fun `brouillons pour la recuperation apres plantage`() {
        val store = ProjectStore(tempDir())
        val f = store.files(store.create("Draft").id)
        f.createFile("behavior_pack/a.json", "{}".toByteArray())
        assertNull(f.pendingDraft("behavior_pack/a.json"))
        f.saveDraft("behavior_pack/a.json", "{\"a\": 1}")
        assertEquals("{\"a\": 1}", f.pendingDraft("behavior_pack/a.json"))
        assertEquals(listOf("behavior_pack/a.json"), f.allDrafts())
        f.clearDraft("behavior_pack/a.json")
        assertNull(f.pendingDraft("behavior_pack/a.json"))
    }

    @Test
    fun `recherche d'un identifiant`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val hits = store.files(meta.id).search("exemple_bms:sanglier_mousses")
        assertTrue(hits.any { it.path == "behavior_pack/entities/sanglier_mousses.json" })
        assertTrue(hits.any { it.path == "resource_pack/entity/sanglier_mousses.entity.json" })
    }

    @Test
    fun `corbeille des projets et projets corrompus signales sans suppression`() {
        val root = tempDir()
        val store = ProjectStore(root)
        val id = store.create("A supprimer").id
        store.moveToTrash(id)
        assertTrue(store.list().isEmpty())
        val restoredId = store.restoreFromTrash(store.listTrash().single().trashName)
        assertEquals("A supprimer", store.load(restoredId).name)
        File(root, "cassé").mkdirs(); File(root, "cassé/project.json").writeText("{ pas du json")
        assertEquals(1, store.broken().size)
        assertTrue(File(root, "cassé/project.json").exists())
    }

    @Test
    fun `validateur - doublons, references manquantes, UUID dupliques`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val f = store.files(meta.id)
        val ns = meta.namespace
        f.duplicate("behavior_pack/entities/sanglier_mousses.json")
        f.writeText("resource_pack/entity/autre.entity.json", """
            {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
              "identifier": "$ns:autre",
              "textures": {"default": "textures/entity/$ns/absente", "v": "textures/entity/pig/pig"},
              "geometry": {"default": "geometry.$ns.absente"}
            }}}
        """.trimIndent())
        val rpManifest = f.readText("resource_pack/manifest.json")
        val bpUuid = (JsonParser.parseOrThrow(f.readText("behavior_pack/manifest.json")).path("header", "uuid"))!!.asString!!
        val rpUuid = JsonParser.parseOrThrow(rpManifest).path("header", "uuid")!!.asString!!
        f.writeText("resource_pack/manifest.json", rpManifest.replace(rpUuid, bpUuid))

        val issues = validate(store, meta.id).issues
        fun has(code: String, sev: Severity) = issues.any { it.code == code && it.severity == sev }
        assertTrue(has("IDENTIFIER_DUPLICATE", Severity.ERROR))
        assertTrue(has("TEXTURE_MISSING", Severity.ERROR))     // espace de noms du projet → erreur
        assertTrue(has("TEXTURE_MISSING", Severity.WARNING))   // texture vanilla possible → avertissement
        assertTrue(has("GEOMETRY_MISSING", Severity.ERROR))
        assertTrue(has("UUID_DUPLICATE", Severity.ERROR))
        assertTrue(has("CLIENT_WITHOUT_BEHAVIOR", Severity.WARNING))
    }

    @Test
    fun `validateur - JSON casse localise`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Cassé")
        store.files(meta.id).createFile("behavior_pack/items/a.json", "{\n \"format_version\": \"1.26.30\",\n}".toByteArray())
        val e = validate(store, meta.id).errors.single { it.code == "JSON_SYNTAX" }
        assertEquals("behavior_pack/items/a.json", e.file)
        assertEquals(3, e.line)
    }

    @Test
    fun `profils de versions`() {
        assertEquals("1.26.50", BedrockVersions.closestFor(listOf(1, 26, 60)).id)
        assertEquals("1.26.40", BedrockVersions.closestFor(listOf(1, 26, 45)).id)
        assertEquals("1.26.30", BedrockVersions.closestFor(listOf(1, 20, 0)).id)
        assertEquals("2.8.0", BedrockVersions.V1_26_30.scriptServerVersion)
    }
}
