package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.DimensionGenerator
import com.bedrockmodstudio.core.bedrock.DimensionSpec
import com.bedrockmodstudio.core.bedrock.DimensionTerrain
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.Js
import com.bedrockmodstudio.core.bedrock.ScriptSetup
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.validation.Validator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests des scripts. Les scripts produits sont aussi copiés dans `build/scripts-check/` :
 * l'étape « scripts » de la CI les vérifie avec TypeScript contre les définitions officielles @minecraft/server.
 */
class ScriptsTest {
    companion object {
        val CHECK_DIR = File("build/scripts-check")

        /** Copie les scripts d'un projet pour la vérification TypeScript. */
        fun exportForTypeCheck(store: ProjectStore, id: String, name: String) {
            val src = File(store.dirOf(id), "behavior_pack/scripts")
            val dst = File(CHECK_DIR, name)
            dst.deleteRecursively()
            src.copyRecursively(dst, overwrite = true)
            File(CHECK_DIR, "tsconfig.json").writeText("""
                {
                  "compilerOptions": {
                    "allowJs": true, "checkJs": true, "noEmit": true, "strict": false,
                    "target": "ES2022", "module": "ES2022", "moduleResolution": "node",
                    "lib": ["ES2022"], "types": [], "skipLibCheck": true
                  },
                  "include": ["**/*.js"]
                }
            """.trimIndent())
        }
    }

    private fun validate(store: ProjectStore, id: String) = store.load(id).let {
        Validator(store.dirOf(id), it.namespace, BedrockVersions.byId(it.targetVersion)).run()
    }

    @Test
    fun `chaines JS correctement echappees`() {
        assertEquals("\"l'\\\"île\\\"\\n\"", Js.str("l'\"île\"\n"))
        assertEquals("../scripts/bms/a.js", ScriptSetup.relative("behavior_pack/other", "behavior_pack/scripts/bms/a.js"))
        assertEquals("bms/a.js", ScriptSetup.relative("behavior_pack/scripts", "behavior_pack/scripts/bms/a.js"))
    }

    @Test
    fun `dimensions - manifeste, point d'entree, validation sans erreur`() {
        for (terrain in DimensionTerrain.entries) {
            val store = ProjectStore(tempDir())
            val meta = store.create("Monde ${terrain.name}")
            val spec = DimensionSpec("Royaume ${terrain.name}", shortId = "royaume", terrain = terrain)
            val r = ContentInstaller.install(store, meta.id, DimensionGenerator.generate(meta.namespace, spec, BedrockVersions.DEFAULT), false, "test")
            assertTrue(r.written.contains("behavior_pack/manifest.json"), r.written.toString())
            val files = store.files(meta.id)
            val manifest = JsonParser.parseOrThrow(files.readText("behavior_pack/manifest.json")) as JsonObject
            val modules = manifest.arr("modules")!!.items.map { it as JsonObject }
            assertEquals("scripts/main.js", modules.single { it.str("type") == "script" }.str("entry"))
            val dep = manifest.arr("dependencies")!!.items.map { it as JsonObject }.single { it.str("module_name") == "@minecraft/server" }
            assertEquals(BedrockVersions.DEFAULT.scriptServerVersion, dep.str("version"))
            val main = files.readText("behavior_pack/scripts/main.js")
            assertTrue(main.contains("import \"./bms/dimension_royaume.js\";"), main)
            assertTrue(files.readText("behavior_pack/scripts/bms/dimension_royaume.js").contains("registerCustomDimension(DIMENSION_ID)"))
            assertTrue(files.exists("behavior_pack/blocks/royaume_portal.json"))
            assertTrue(files.exists("behavior_pack/items/royaume_key.json"))
            val report = validate(store, meta.id)
            assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
            exportForTypeCheck(store, meta.id, "dimension_${terrain.name.lowercase()}")
        }
    }

    @Test
    fun `point d'entree utilisateur conserve, imports sans doublon`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Scripts perso")
        val files = store.files(meta.id)
        val content = DimensionGenerator.generate(meta.namespace, DimensionSpec("Abysse"), BedrockVersions.DEFAULT)
        ContentInstaller.install(store, meta.id, content, false, "t")
        files.writeText("behavior_pack/scripts/main.js", files.readText("behavior_pack/scripts/main.js") + "\nconsole.warn(\"mon code\");\n")
        ContentInstaller.install(store, meta.id, content, true, "t")
        val main = files.readText("behavior_pack/scripts/main.js")
        assertEquals(1, Regex("dimension_abysse").findAll(main).count())
        assertTrue(main.contains("console.warn(\"mon code\");"))
        // Le module de script n'est déclaré qu'une fois.
        val manifest = JsonParser.parseOrThrow(files.readText("behavior_pack/manifest.json")) as JsonObject
        assertEquals(1, manifest.arr("modules")!!.items.count { (it as JsonObject).str("type") == "script" })
    }

    @Test
    fun `validation des scripts - import manquant, dependance absente, accolade`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Scripts casses")
        val files = store.files(meta.id)
        val c = GeneratedContent()
        c.script("bms/a.js", "import { world } from \"@minecraft/server-ui\";\nimport \"./absent.js\";\nimport x from \"lodash\";\nfunction f() {\n")
        ContentInstaller.install(store, meta.id, c, false, "t")
        val codes = validate(store, meta.id).errors.map { it.code }.toSet()
        assertTrue("SCRIPT_DEPENDENCY_MISSING" in codes, codes.toString())
        assertTrue("SCRIPT_IMPORT_MISSING" in codes, codes.toString())
        assertTrue("SCRIPT_IMPORT_UNSUPPORTED" in codes, codes.toString())
        assertTrue("SCRIPT_SYNTAX" in codes, codes.toString())
        assertFalse(files.readText("behavior_pack/scripts/main.js").isBlank())
    }

    @Test
    fun `version ciblee trop ancienne refusee`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Ancien")
        val content = DimensionGenerator.generate(meta.namespace, DimensionSpec("X"), BedrockVersions.DEFAULT)
        content.requiredFeatures += com.bedrockmodstudio.core.bedrock.BedrockFeature.entries // toutes : OK pour les profils actuels
        ContentInstaller.install(store, meta.id, content, false, "t")
        assertFailsWith<IllegalArgumentException> { DimensionGenerator.generate(meta.namespace, DimensionSpec("X", withPortal = false, withKey = false, withCommand = false), BedrockVersions.DEFAULT) }
        assertFailsWith<UserFacingException> {
            ContentInstaller.install(ProjectStore(tempDir()).let { s -> s }, "inexistant", GeneratedContent(), false, "t")
        }
    }
}
