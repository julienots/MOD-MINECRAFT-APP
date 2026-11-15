package com.bedrockmodstudio.ui.mods

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.bedrockmodstudio.core.model3d.Animation
import com.bedrockmodstudio.core.model3d.AnimationCodec
import com.bedrockmodstudio.core.model3d.BonePose
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.mods.ModCatalog
import com.bedrockmodstudio.core.mods.PreviewMeshes
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.ui.model.renderStill

/**
 * Tout ce qu'il faut pour afficher un contenu en 3D :
 * - créature ou bloc avec modèle : le modèle .geo.json et sa texture (animations jouables) ;
 * - bloc plein : un cube avec ses textures dessus / dessous / côtés ;
 * - objet : son icône en relief, comme tenue en main ;
 * - sinon : une boîte grise, pour que rien ne reste vide.
 */
class ModPreview(
    val model: GeoModel?,
    val texture: Bitmap?,
    val textureWidth: Int,
    val textureHeight: Int,
    private val staticFaces: List<MeshFace>?,
    val faceColor: ((MeshFace) -> Int)?,
    val animations: List<Animation>,
) {
    fun faces(poses: Map<String, BonePose> = emptyMap()): List<MeshFace> =
        staticFaces ?: model?.let { MeshBuilder.build(it, poses) } ?: emptyList()

    fun thumbnail(size: Int, yaw: Float = 35f): Bitmap =
        renderStill(faces(), texture, textureWidth, textureHeight, size, size, yaw = yaw, faceColor = faceColor, background = 0xFF1C2028.toInt())

    companion object {
        private fun decode(files: ProjectFiles, path: String?): Bitmap? = path?.let {
            runCatching {
                val b = files.readBytes(it)
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inScaled = false })
            }.getOrNull()
        }

        private fun loadModel(files: ProjectFiles, path: String?, id: String?): GeoModel? {
            path ?: return null
            val geo = runCatching { GeoCodec.parse(files.readText(path)) }.getOrNull() ?: return null
            return geo.models.firstOrNull { it.identifier == id } ?: geo.models.firstOrNull()
        }

        fun load(files: ProjectFiles, e: ModCatalog.Entry): ModPreview {
            val model = loadModel(files, e.geometryPath, e.geometryId)
            if (model != null) {
                val tex = decode(files, e.texturePath)
                val anims = e.animations.mapNotNull { ref ->
                    ref.path ?: return@mapNotNull null
                    runCatching { AnimationCodec.parse(files.readText(ref.path!!)).animations.firstOrNull { it.name == ref.id } }.getOrNull()
                        ?.copy(name = ref.shortName)
                }.filter { it.bones.isNotEmpty() }
                return ModPreview(model, tex, model.textureWidth, model.textureHeight, null, if (tex == null) grey else null, anims)
            }
            return when (e.kind) {
                ModCatalog.Kind.BLOCK -> blockPreview(files, e)
                ModCatalog.Kind.ITEM -> itemPreview(files, e)
                ModCatalog.Kind.ENTITY -> fallback()
            }
        }

        private val grey: (MeshFace) -> Int = { 0xFF8FA3B8.toInt() }

        private fun fallback(): ModPreview {
            val cube = PreviewMeshes.blockCube()
            return ModPreview(cube, null, cube.textureWidth, cube.textureHeight, null, grey, emptyList())
        }

        private fun blockPreview(files: ProjectFiles, e: ModCatalog.Entry): ModPreview {
            val f = e.blockFaces ?: return fallback()
            val up = decode(files, f.up ?: f.any); val down = decode(files, f.down ?: f.any); val side = decode(files, f.side ?: f.any)
            if (up == null && down == null && side == null) return fallback()
            // Image assemblée dessus | dessous | côté (16×16 chacune), lue par le cube d'aperçu.
            val atlas = Bitmap.createBitmap(PreviewMeshes.BLOCK_ATLAS_WIDTH, PreviewMeshes.BLOCK_ATLAS_HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(atlas)
            val p = Paint().apply { isFilterBitmap = false }
            listOf(up ?: side ?: down, down ?: side ?: up, side ?: up ?: down).forEachIndexed { i, b ->
                // Les textures animées (bandes verticales) : seule la première image est utilisée.
                if (b != null) c.drawBitmap(b, Rect(0, 0, b.width, minOf(b.width, b.height)), Rect(i * 16, 0, i * 16 + 16, 16), p)
            }
            val cube = PreviewMeshes.blockCube()
            return ModPreview(cube, atlas, cube.textureWidth, cube.textureHeight, null, null, emptyList())
        }

        private fun itemPreview(files: ProjectFiles, e: ModCatalog.Entry): ModPreview {
            val icon = decode(files, e.texturePath) ?: return fallback()
            val h = minOf(icon.width, icon.height)
            val px = IntArray(icon.width * h)
            icon.getPixels(px, 0, icon.width, 0, 0, icon.width, h)
            val mesh = PreviewMeshes.itemVoxels(px, icon.width, h)
            if (mesh.faces.isEmpty()) return fallback()
            return ModPreview(null, null, 16, 16, mesh.faces, { mesh.colors[it.cubeIndex] }, emptyList())
        }
    }
}
