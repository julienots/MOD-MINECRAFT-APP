package com.bedrockmodstudio.ui.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model3d.Animation
import com.bedrockmodstudio.core.model3d.AnimationCodec
import com.bedrockmodstudio.core.model3d.AnimationFile
import com.bedrockmodstudio.core.model3d.Bone
import com.bedrockmodstudio.core.model3d.BonePose
import com.bedrockmodstudio.core.model3d.Channel
import com.bedrockmodstudio.core.model3d.Cube
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.GeoFile
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.Keyframe
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.core.storage.ErrorLog
import com.bedrockmodstudio.core.storage.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelEditorViewModel(
    private val files: ProjectFiles,
    val path: String,
    private val errorLog: ErrorLog,
) : ViewModel() {
    var geo by mutableStateOf<GeoFile?>(null); private set
    var modelIndex by mutableStateOf(0); private set
    var selBone by mutableStateOf<Int?>(null)
    var selCube by mutableStateOf<Int?>(null)
    var dirty by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var texture by mutableStateOf<Bitmap?>(null); private set
    var texturePath by mutableStateOf<String?>(null); private set
    var textures by mutableStateOf<List<String>>(emptyList()); private set
    var lighting by mutableStateOf(true)
    var lightAngle by mutableStateOf(45f)

    // Animations
    var animPath by mutableStateOf(""); private set
    var anims by mutableStateOf<AnimationFile?>(null); private set
    var animIndex by mutableStateOf<Int?>(null)
    var time by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false); private set
    var animDirty by mutableStateOf(false); private set
    var channel by mutableStateOf(Channel.ROTATION)
    private var playJob: Job? = null

    private val undo = ArrayDeque<GeoFile>()
    private val redo = ArrayDeque<GeoFile>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    val model: GeoModel? get() = geo?.models?.getOrNull(modelIndex)
    val animation: Animation? get() = animIndex?.let { anims?.animations?.getOrNull(it) }
    val poses: Map<String, BonePose> get() = animation?.sample(time) ?: emptyMap()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    geo = GeoCodec.parse(files.readText(path))
                    textures = files.walk("resource_pack").filter { it.endsWith(".png") }
                    guessTexture()?.let { loadTexture(it) }
                    animPath = "resource_pack/animations/" + path.substringAfterLast('/').removeSuffix(".json").removeSuffix(".geo") + ".animation.json"
                    if (files.exists(animPath)) anims = runCatching { AnimationCodec.parse(files.readText(animPath)) }
                        .onFailure { message = "Animations illisibles : ${it.message}" }.getOrNull()
                }
            } catch (e: Exception) {
                errorLog.log("Éditeur 3D : $path", e)
                error = e.message ?: "Erreur de lecture"
            }
        }
    }

    /** Texture utilisée par l'entité client qui référence ce modèle, sinon une texture du même nom. */
    private fun guessTexture(): String? {
        val id = model?.identifier ?: return null
        for (p in files.walk("resource_pack").filter { it.endsWith(".json") && (it.contains("/entity/") || it.contains("/attachables/")) }) {
            val v = runCatching { JsonParser.parse(files.readText(p)).valueOrNull() }.getOrNull() ?: continue
            val desc = v.path("minecraft:client_entity", "description") ?: v.path("minecraft:attachable", "description") ?: continue
            val geos = (desc.path("geometry") as? com.bedrockmodstudio.core.json.JsonObject)?.entries?.mapNotNull { it.value.asString } ?: continue
            if (id in geos) {
                val tex = (desc.path("textures") as? com.bedrockmodstudio.core.json.JsonObject)?.entries?.firstOrNull()?.value?.asString ?: continue
                val png = "resource_pack/$tex.png"
                if (files.exists(png)) return png
            }
        }
        val base = path.substringAfterLast('/').substringBefore('.')
        return textures.firstOrNull { it.substringAfterLast('/').substringBefore('.') == base }
    }

    fun loadTexture(p: String) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = files.readBytes(p)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false })
                }.getOrNull()
            }
            texture = bmp; texturePath = if (bmp != null) p else null
            if (bmp == null) message = "Texture illisible : $p"
        }
    }

    fun reloadTexture() { texturePath?.let(::loadTexture) }

    // ---- Édition du modèle ----

    fun edit(change: (GeoModel) -> GeoModel) {
        val g = geo ?: return
        val m = model ?: return
        undo.addLast(g); if (undo.size > 100) undo.removeFirst()
        redo.clear()
        geo = g.copy(models = g.models.toMutableList().also { it[modelIndex] = change(m) })
        dirty = true
    }

    fun undo() { val p = undo.removeLastOrNull() ?: return; geo?.let { redo.addLast(it) }; geo = p; dirty = true; clampSelection() }
    fun redo() { val n = redo.removeLastOrNull() ?: return; geo?.let { undo.addLast(it) }; geo = n; dirty = true; clampSelection() }

    private fun clampSelection() {
        val m = model ?: return
        if (selBone != null && selBone!! !in m.bones.indices) { selBone = null; selCube = null }
        val b = selBone?.let { m.bones[it] }
        if (b != null && selCube != null && selCube!! !in b.cubes.indices) selCube = null
    }

    fun select(bone: Int?, cube: Int?) { selBone = bone; selCube = cube }

    private fun uniqueBoneName(base: String): String {
        val names = model?.bones?.map { it.name }?.toSet() ?: emptySet()
        if (base !in names) return base
        var i = 2
        while ("$base$i" in names) i++
        return "$base$i"
    }

    fun addBone() {
        val parent = selBone?.let { model?.bones?.getOrNull(it) }
        val name = uniqueBoneName("os")
        edit { it.copy(bones = it.bones + Bone(name, parent = parent?.name, pivot = parent?.pivot ?: Vec3.ZERO)) }
        selBone = model!!.bones.lastIndex; selCube = null
    }

    fun addCube() {
        val bi = selBone ?: run { if (model?.bones.isNullOrEmpty()) { addBone() }; selBone ?: 0 }
        edit { m -> m.copy(bones = m.bones.mapIndexed { i, b ->
            if (i == bi) b.copy(cubes = b.cubes + Cube(b.pivot - Vec3(2.0, 0.0, 2.0), Vec3(4.0, 4.0, 4.0), 0.0 to 0.0)) else b
        }) }
        selBone = bi; selCube = model!!.bones[bi].cubes.lastIndex
    }

    fun deleteSelected() {
        val bi = selBone ?: return
        val ci = selCube
        edit { m ->
            if (ci != null) m.copy(bones = m.bones.mapIndexed { i, b -> if (i == bi) b.copy(cubes = b.cubes.filterIndexed { j, _ -> j != ci }) else b })
            else {
                // Supprimer un os supprime aussi ses descendants (sinon ils auraient un parent inexistant).
                val removed = mutableSetOf(m.bones[bi].name)
                var changed = true
                while (changed) { changed = false; m.bones.forEach { if (it.parent in removed && removed.add(it.name)) changed = true } }
                m.copy(bones = m.bones.filter { it.name !in removed })
            }
        }
        selCube = null; if (ci == null) selBone = null
    }

    fun duplicateSelected(mirrorX: Boolean) {
        val bi = selBone ?: return
        val m = model ?: return
        val ci = selCube
        fun mirror(c: Cube) = if (!mirrorX) c.copy(origin = c.origin + Vec3(c.size.x, 0.0, 0.0))
        else c.copy(origin = Vec3(-(c.origin.x + c.size.x), c.origin.y, c.origin.z), pivot = c.pivot?.let { Vec3(-it.x, it.y, it.z) },
            rotation = c.rotation?.let { Vec3(it.x, -it.y, -it.z) }, mirror = !c.mirror)
        if (ci != null) {
            val cube = m.bones[bi].cubes[ci]
            edit { mm -> mm.copy(bones = mm.bones.mapIndexed { i, b -> if (i == bi) b.copy(cubes = b.cubes + mirror(cube)) else b }) }
            selCube = model!!.bones[bi].cubes.lastIndex
        } else {
            val b = m.bones[bi]
            val name = uniqueBoneName(if (mirrorX) b.name.replace("left", "right").replace("gauche", "droite").let { if (it == b.name) b.name + "_miroir" else it } else b.name + "_copie")
            val copy = b.copy(name = name, cubes = b.cubes.map(::mirror),
                pivot = if (mirrorX) Vec3(-b.pivot.x, b.pivot.y, b.pivot.z) else b.pivot,
                rotation = if (mirrorX) Vec3(b.rotation.x, -b.rotation.y, -b.rotation.z) else b.rotation)
            edit { mm -> mm.copy(bones = mm.bones + copy) }
            selBone = model!!.bones.lastIndex
        }
    }

    fun updateBone(change: (Bone) -> Bone) {
        val bi = selBone ?: return
        edit { m ->
            val old = m.bones[bi]; val new = change(old)
            m.copy(bones = m.bones.mapIndexed { i, b ->
                when {
                    i == bi -> new
                    old.name != new.name && b.parent == old.name -> b.copy(parent = new.name) // les enfants suivent le renommage
                    else -> b
                }
            })
        }
    }

    fun updateCube(change: (Cube) -> Cube) {
        val bi = selBone ?: return; val ci = selCube ?: return
        edit { m -> m.copy(bones = m.bones.mapIndexed { i, b -> if (i == bi) b.copy(cubes = b.cubes.mapIndexed { j, c -> if (j == ci) change(c) else c }) else b }) }
    }

    fun updateModel(change: (GeoModel) -> GeoModel) = edit(change)

    fun save(onDone: (Boolean) -> Unit) {
        val g = geo ?: return
        val problems = g.models.flatMap { it.validate() }
        if (problems.isNotEmpty()) { message = problems.joinToString("\n"); onDone(false); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    files.writeText(path, GeoCodec.write(g), "Éditeur 3D")
                    if (animDirty) anims?.let { files.writeText(animPath, AnimationCodec.write(it), "Éditeur d'animations") }
                }.onFailure { errorLog.log("Enregistrement du modèle", it); message = it.message }.isSuccess
            }
            if (ok) { dirty = false; animDirty = false }
            onDone(ok)
        }
    }

    fun exportObj(dir: File): File? = model?.let { m ->
        dir.mkdirs()
        File(dir, path.substringAfterLast('/').substringBefore('.') + ".obj").also { it.writeText(MeshBuilder.toObj(m)) }
    }

    // ---- Animations ----

    private fun editAnim(change: (Animation) -> Animation) {
        val f = anims ?: return; val i = animIndex ?: return
        anims = f.copy(animations = f.animations.toMutableList().also { it[i] = change(it[i]) })
        animDirty = true; dirty = true
    }

    fun newAnimation(kind: String, label: String) {
        val m = model ?: return
        val base = m.identifier.removePrefix("geometry.")
        val name = "animation.$base.${com.bedrockmodstudio.core.model.Identifiers.slug(label).ifEmpty { kind }}"
        val legs = m.bones.map { it.name }.filter { it.contains("leg") || it.contains("jambe") || it.contains("arm") || it.contains("bras") }
        val targets = when (kind) { "walk" -> legs.ifEmpty { m.bones.map { it.name }.take(2) }; else -> listOf(selBone?.let { m.bones[it].name } ?: m.bones.firstOrNull()?.name ?: "root") }
        val f = anims ?: AnimationFile("1.8.0", emptyList())
        val unique = generateSequence(name) { it + "_2" }.first { n -> f.animations.none { it.name == n } }
        anims = f.copy(animations = f.animations + AnimationCodec.template(kind, unique, targets))
        animIndex = anims!!.animations.lastIndex; time = 0.0; animDirty = true; dirty = true
    }

    fun setLength(l: Double) = editAnim { it.copy(length = l.coerceIn(0.05, 600.0)) }
    fun setLoop(v: Boolean) = editAnim { it.copy(loop = v) }

    /** Ajoute (ou remplace) une image clé pour l'os sélectionné à l'instant courant. */
    fun keyframe(value: Vec3) {
        val bone = selBone?.let { model?.bones?.getOrNull(it)?.name } ?: run { message = "Sélectionne d'abord un os."; return }
        editAnim { it.withKeyframe(bone, channel, Keyframe(Math.round(time * 100) / 100.0, value)) }
    }

    fun deleteKeyframe(t: Double) {
        val bone = selBone?.let { model?.bones?.getOrNull(it)?.name } ?: return
        editAnim { it.withoutKeyframe(bone, channel, t) }
    }

    fun togglePlay() {
        if (playing) { playJob?.cancel(); playing = false; return }
        val a = animation ?: return
        playing = true
        playJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(33)
                val now = System.nanoTime()
                val next = time + (now - last) / 1e9
                last = now
                time = if (next > a.length) { if (a.loop) next % a.length else { playing = false; a.length } } else next
                if (!playing) break
            }
        }
    }
}
