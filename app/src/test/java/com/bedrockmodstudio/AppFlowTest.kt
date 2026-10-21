package com.bedrockmodstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Parcours réels dans l'interface, exécutés avec Robolectric (Android simulé sur la JVM).
 * Ils vérifient que les écrans s'affichent, que les boutons agissent et que les fichiers sont produits.
 * Ils ne remplacent pas un essai sur un vrai téléphone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi") // format d'un téléphone courant
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(text: String, substring: Boolean = false) {
        try {
            rule.waitUntil(15_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("Texte « $text » absent. Écran actuel :\n" + rule.onRoot().printToString(maxDepth = 30), e)
        }
    }

    private fun click(text: String) {
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() } // seulement si l'élément est dans une zone défilante
        node.performClick()
    }

    private fun back() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    private val app get() = rule.activity.container

    /** Ouvre un fichier en touchant chaque dossier dans l'explorateur, en faisant défiler la liste si besoin. */
    private fun openInExplorer(path: String) {
        click("Fichiers")
        for (segment in path.split('/')) {
            rule.waitUntil(15_000) { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(segment))
            rule.onNode(hasText(segment) and androidx.compose.ui.test.hasClickAction()).performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun `projet d'exemple - verification puis export mcaddon`() {
        click("Ouvrir le projet d'exemple")
        waitText("Exemple — Sanglier des mousses")
        click("Vérifier")
        waitText("Aucune erreur : le projet peut être exporté.")
        back()
        waitText("Exporter")
        click("Exporter")
        click("Addon complet (.mcaddon)")
        waitText("Fichier prêt", substring = true)
        rule.onNodeWithText("Enregistrer dans…").assertExists()
        rule.onNodeWithText("Ouvrir avec Minecraft").assertExists()
        val exported = File(rule.activity.cacheDir, "exports").listFiles()!!.filter { it.name.endsWith(".mcaddon") }
        assertEquals(1, exported.size)
        assertTrue(exported.single().length() > 1000)
    }

    @Test
    fun `nouveau projet puis assistant creature`() {
        click("Créer un mod")
        waitText("Nouveau mod")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Forêt sombre")
        click("Créer")
        waitText("Créer une créature")
        rule.onNodeWithText("Forêt sombre").assertExists()
        click("Créer une créature")
        waitText("Nouvelle créature")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Loup gris")
        rule.onNode(hasText("Hostile")).performScrollTo().performClick()
        click("Générer la créature")
        waitText("Fichiers créés ou modifiés", substring = true)

        val project = app.store.list().single()
        assertEquals("foret_sombre", project.meta.namespace)
        val files = app.store.files(project.meta.id)
        assertTrue(files.readText("behavior_pack/entities/loup_gris.json").contains("minecraft:behavior.nearest_attackable_target"))
        assertTrue(files.exists("resource_pack/textures/entity/loup_gris.png"))
        assertTrue(app.exporter.validate(project.meta.id).errors.isEmpty())
    }

    @Test
    fun `editeur - erreur JSON detectee, enregistrement confirme, historique conserve`() {
        click("Ouvrir le projet d'exemple")
        waitText("Fichiers")
        click("Fichiers")
        waitText("behavior_pack")
        click("behavior_pack")
        rule.waitUntil(15_000) { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("manifest.json"))
        click("manifest.json")
        waitText("JSON valide")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("x")
        waitText("Ligne 1, colonne 1", substring = true)
        rule.onNodeWithContentDescription("Enregistrer").performClick()
        waitText("JSON invalide")
        rule.onAllNodesWithText("Enregistrer")[0].performClick()
        rule.waitForIdle()

        val project = app.store.list().single()
        val files = app.store.files(project.meta.id)
        rule.waitUntil(10_000) { files.readText("behavior_pack/manifest.json").startsWith("x") }
        assertEquals(1, files.history("behavior_pack/manifest.json").size)
        // L'export est maintenant bloqué par l'erreur.
        assertTrue(app.exporter.validate(project.meta.id).errors.any { it.code == "JSON_SYNTAX" })
    }

    @Test
    fun `parametres - journal et version par defaut`() {
        click("Paramètres")
        waitText("Version ciblée par défaut")
        click("Minecraft 1.26.30")
        assertEquals("1.26.30", app.defaultVersion)
        rule.onNodeWithText("Aucune erreur enregistrée.").performScrollTo().assertExists()
    }

    @Test
    fun `assistants objet avec recette, bloc et recette au four`() {
        click("Créer un mod")
        waitText("Nouveau mod")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Rubis")
        click("Créer")
        waitText("Créer un objet")

        click("Créer un objet")
        waitText("Nouvel objet")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Épée de rubis")
        click("Ajouter la recette de fabrication")
        rule.onNodeWithText("Ajouter la recette de fabrication").performScrollTo()
        rule.onAllNodes(androidx.compose.ui.test.isToggleable())[1].performScrollTo().performClick()
        click("Générer l'objet")
        waitText("Fichiers créés ou modifiés", substring = true)
        back()

        waitText("Créer un bloc")
        click("Créer un bloc")
        waitText("Nouveau bloc")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Minerai de rubis")
        click("Minerai")
        click("Générer le bloc")
        waitText("Fichiers créés ou modifiés", substring = true)
        back()

        waitText("Créer une recette")
        click("Créer une recette")
        waitText("Nouvelle recette")
        click("Four")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("rubis:minerai_de_rubis")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("minecraft:emerald")
        click("Générer la recette")
        waitText("Fichiers créés ou modifiés", substring = true)

        val project = app.store.list().single()
        val files = app.store.files(project.meta.id)
        assertTrue(files.exists("behavior_pack/items/epee_de_rubis.json"))
        assertTrue(files.exists("behavior_pack/recipes/epee_de_rubis.json"))
        assertTrue(files.exists("behavior_pack/blocks/minerai_de_rubis.json"))
        assertTrue(files.exists("behavior_pack/recipes/emerald_four.json"))
        val report = app.exporter.validate(project.meta.id)
        assertTrue(report.issues.joinToString("\n"), report.errors.isEmpty())
    }

    @Test
    fun `editeur de textures - dessin au doigt, annuler, enregistrer`() {
        click("Ouvrir le projet d'exemple")
        waitText("Fichiers")
        val project = app.store.list().single()
        val path = "resource_pack/textures/items/croc.png"
        val before = app.store.files(project.meta.id).readBytes(path)
        openInExplorer(path)
        waitText("Modifier dans l'éditeur de textures")
        click("Modifier dans l'éditeur de textures")
        waitText("Crayon")
        rule.waitForIdle()
        val canvas = rule.onNode(androidx.compose.ui.test.hasTestTag("pixel_canvas"))
        canvas.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(120f, 40f)); up() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Annuler la modification").assertIsEnabled()
        rule.onNodeWithContentDescription("Enregistrer").performClick()
        rule.waitUntil(10_000) { !app.store.files(project.meta.id).readBytes(path).contentEquals(before) }
        val after = app.store.files(project.meta.id).readBytes(path)
        assertEquals(com.bedrockmodstudio.core.image.PngInfo.Size(16, 16), com.bedrockmodstudio.core.image.PngInfo.read(after))
        assertEquals(1, app.store.files(project.meta.id).history(path).size)
    }

    @Test
    fun `assistant dimension personnalisee`() {
        click("Créer un mod")
        waitText("Nouveau mod")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Mondes")
        click("Créer")
        waitText("Créer une dimension")
        click("Créer une dimension")
        waitText("Nouvelle dimension")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Abysse")
        click("Plaine plate")
        click("Générer la dimension")
        waitText("Fichiers créés ou modifiés", substring = true)
        val project = app.store.list().single()
        val files = app.store.files(project.meta.id)
        assertTrue(files.readText("behavior_pack/scripts/main.js").contains("dimension_abysse.js"))
        assertTrue(files.readText("behavior_pack/scripts/bms/dimension_abysse.js").contains("\"FLAT\""))
        val report = app.exporter.validate(project.meta.id)
        assertTrue(report.issues.joinToString("\n"), report.errors.isEmpty())
    }
}
