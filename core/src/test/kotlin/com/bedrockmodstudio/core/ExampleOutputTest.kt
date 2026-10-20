package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.packaging.ExportKind
import com.bedrockmodstudio.core.packaging.ProjectExporter
import com.bedrockmodstudio.core.storage.ProjectStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Produit l'addon d'exemple dans core/build/exemples/ pour pouvoir le tester à la main dans Minecraft. */
class ExampleOutputTest {
    @Test
    fun `genere l'addon d'exemple`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val out = File("build/exemples").apply { deleteRecursively(); mkdirs() }
        val r = ok(ProjectExporter(store).export(meta.id, ExportKind.MCADDON, out, bumpVersion = false))
        assertTrue(r.file.length() > 0)
        store.dirOf(meta.id).copyRecursively(File(out, "projet"), overwrite = true)
    }
}
