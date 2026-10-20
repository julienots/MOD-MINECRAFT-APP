package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.packaging.ExportKind
import com.bedrockmodstudio.core.packaging.ExportOutcome
import com.bedrockmodstudio.core.packaging.ImportOutcome
import com.bedrockmodstudio.core.packaging.PackImporter
import com.bedrockmodstudio.core.packaging.ProjectExporter
import com.bedrockmodstudio.core.packaging.SafeZip
import com.bedrockmodstudio.core.packaging.ZipLimits
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackagingTest {

    @Test
    fun `export mcaddon - structure, versions et dependance mises a jour`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val out = tempDir()
        val r = ok(ProjectExporter(store).export(meta.id, ExportKind.MCADDON, out))
        assertEquals("Exemple_Sanglier_des_mousses_v1.0.1.mcaddon", r.file.name)
        val entries = zipEntries(r.file)
        val base = ProjectExporter.safeFileName(meta.name)
        assertTrue("${base}_BP/manifest.json" in entries)
        assertTrue("${base}_RP/manifest.json" in entries)
        assertTrue("${base}_RP/textures/entity/sanglier_mousses.png" in entries)
        assertTrue(entries.none { it.contains(".history") || it.contains("project.json") }, entries.toString())

        val bp = JsonParser.parseOrThrow(zipText(r.file, "${base}_BP/manifest.json")) as JsonObject
        val rp = JsonParser.parseOrThrow(zipText(r.file, "${base}_RP/manifest.json")) as JsonObject
        assertEquals("[1, 0, 1]", bp.path("header", "version").toString())
        assertEquals("[1, 0, 1]", rp.path("header", "version").toString())
        assertEquals("[1, 0, 1]", (bp.arr("dependencies")!![0] as JsonObject)["version"].toString())
        assertEquals("1.0.1", store.load(meta.id).packVersion.toString())

        // Deuxième export : nouvelle version, nouveau nom, aucun écrasement.
        val r2 = ok(ProjectExporter(store).export(meta.id, ExportKind.MCADDON, out))
        assertTrue(r2.file.name.endsWith("_v1.0.2.mcaddon"))
        assertTrue(r.file.exists())
    }

    @Test
    fun `export mcpack - manifeste a la racine`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val out = tempDir()
        val bp = ok(ProjectExporter(store).export(meta.id, ExportKind.MCPACK_BEHAVIOR, out, bumpVersion = false))
        val rp = ok(ProjectExporter(store).export(meta.id, ExportKind.MCPACK_RESOURCE, out, bumpVersion = false))
        assertTrue("manifest.json" in zipEntries(bp.file) && "entities/sanglier_mousses.json" in zipEntries(bp.file))
        assertTrue("manifest.json" in zipEntries(rp.file) && "textures/entity/sanglier_mousses.png" in zipEntries(rp.file))
        assertTrue(bp.file.name.contains("_BP_") && rp.file.name.contains("_RP_"))
    }

    @Test
    fun `export bloque si erreur, aucun fichier produit`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        store.files(meta.id).writeText("behavior_pack/entities/sanglier_mousses.json", "{ cassé")
        val out = tempDir()
        val r = assertIs<ExportOutcome.Blocked>(ProjectExporter(store).export(meta.id, ExportKind.MCADDON, out))
        assertTrue(r.report.errors.any { it.code == "JSON_SYNTAX" })
        assertEquals(0, out.listFiles()!!.size)
        assertEquals("1.0.0", store.load(meta.id).packVersion.toString())
    }

    @Test
    fun `aller-retour export puis import - rien n'est perdu, UUID conserves`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        store.files(meta.id).createFile("resource_pack/fichier_inconnu.xyz", byteArrayOf(1, 2, 3))
        val r = ok(ProjectExporter(store).export(meta.id, ExportKind.MCADDON, tempDir()))

        val imported = assertIs<ImportOutcome.Success>(
            r.file.inputStream().use { PackImporter(store, tempDir()).import(it, r.file.name) }, "import")
        val m2 = imported.meta
        assertEquals(meta.behaviorPack.headerUuid, m2.behaviorPack.headerUuid)
        assertEquals(meta.resourcePack.headerUuid, m2.resourcePack.headerUuid)
        assertEquals("exemple_bms", m2.namespace)
        assertEquals("Exemple — Sanglier des mousses", m2.name)
        val f1 = store.files(meta.id); val f2 = store.files(m2.id)
        assertEquals(f1.walk().filter { it.startsWith("behavior_pack") || it.startsWith("resource_pack") }.toSet(),
            f2.walk().filter { it.startsWith("behavior_pack") || it.startsWith("resource_pack") }.toSet())
        assertTrue(File(store.dirOf(m2.id), ".imported/original/${r.file.name}").isFile)
        assertTrue(imported.report.unknownFiles.any { it.endsWith("fichier_inconnu.xyz") })
        assertEquals(0, imported.report.validationErrors)
        assertTrue((imported.report.recognized["Entités (comportement)"] ?: 0) >= 1)
    }

    @Test
    fun `import d'un mcaddon contenant des mcpack imbriques`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val out = tempDir()
        val bp = ok(ProjectExporter(store).export(meta.id, ExportKind.MCPACK_BEHAVIOR, out, false)).file
        val rp = ok(ProjectExporter(store).export(meta.id, ExportKind.MCPACK_RESOURCE, out, false)).file
        val addon = zipOf("bp.mcpack" to bp.readBytes(), "rp.mcpack" to rp.readBytes())
        val r = assertIs<ImportOutcome.Success>(PackImporter(store, tempDir()).import(addon.inputStream(), "nested.mcaddon"))
        assertEquals(2, r.report.packs.size)
        assertTrue(store.files(r.meta.id).exists("behavior_pack/entities/sanglier_mousses.json"))
        assertTrue(store.files(r.meta.id).exists("resource_pack/textures/entity/sanglier_mousses.png"))
    }

    @Test
    fun `sauvegarde zip puis restauration comme nouveau projet`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val backup = ok(ProjectExporter(store).export(meta.id, ExportKind.BACKUP_ZIP, tempDir())).file
        val r = assertIs<ImportOutcome.Success>(backup.inputStream().use { PackImporter(store, tempDir()).import(it, backup.name) })
        assertTrue(r.meta.name.endsWith("(restauré)"))
        assertEquals(meta.behaviorPack, r.meta.behaviorPack)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `protection zip slip - les chemins dangereux sont ignores`() {
        assertNull(SafeZip.safeEntryName("../../evil.txt"))
        assertNull(SafeZip.safeEntryName("/etc/passwd"))
        assertNull(SafeZip.safeEntryName("C:\\Windows\\x"))
        assertNull(SafeZip.safeEntryName("a/..\\..\\b"))
        assertEquals("a/b.json", SafeZip.safeEntryName("a\\b.json"))
        val dest = tempDir()
        val zip = zipOf("../../evil.txt" to "x".toByteArray(), "ok/manifest.json" to "{}".toByteArray())
        val r = SafeZip.extract(zip.inputStream(), File(dest, "x"))
        assertEquals(listOf("ok/manifest.json"), r.files)
        assertTrue(r.skipped.single().contains("dangereux"))
        assertTrue(!File(dest, "evil.txt").exists() && !File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun `protection bombe zip - taille decompressee plafonnee`() {
        val big = ByteArray(2 * 1024 * 1024) // très compressible
        val zip = zipOf("a.bin" to big)
        assertTrue(zip.size < 100_000)
        val dest = File(tempDir(), "x")
        assertFailsWith<UserFacingException> { SafeZip.extract(zip.inputStream(), dest, ZipLimits(maxEntryBytes = 1024 * 1024)) }
        assertTrue(!dest.exists())
        assertFailsWith<UserFacingException> {
            SafeZip.extract(zipOf("a" to ByteArray(1), "b" to ByteArray(1), "c" to ByteArray(1)).inputStream(), File(tempDir(), "y"), ZipLimits(maxEntries = 2))
        }
    }

    @Test
    fun `refus explique des mods Java, des mondes et des formats inconnus`() {
        val store = ProjectStore(tempDir())
        val imp = PackImporter(store, tempDir())
        assertTrue(assertIs<ImportOutcome.Rejected>(imp.import(ByteArray(4).inputStream(), "mod.jar")).message.contains("Java"))
        val javaPack = zipOf("pack.mcmeta" to "{}".toByteArray(), "assets/x.png" to ByteArray(3))
        assertTrue(assertIs<ImportOutcome.Rejected>(imp.import(javaPack.inputStream(), "pack.zip")).message.contains("Java"))
        assertTrue(assertIs<ImportOutcome.Rejected>(imp.import("pas un zip".byteInputStream(), "x.mcpack")).message.isNotBlank())
        assertIs<ImportOutcome.Rejected>(imp.import(ByteArray(1).inputStream(), "x.rar"))
        assertTrue(store.list().isEmpty())
    }
}
