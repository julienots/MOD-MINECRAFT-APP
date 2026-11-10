package com.bedrockmodstudio.core.model3d

import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.UserFacingException

/** Placement d'un objet dans la main : décalage (pixels), rotation (degrés) et taille (facteur). */
data class HoldTransform(val position: Vec3 = Vec3.ZERO, val rotation: Vec3 = Vec3.ZERO, val scale: Double = 1.0) {
    fun pose() = BonePose(rotation, position, Vec3(scale, scale, scale))
    fun clamped() = HoldTransform(
        Vec3(position.x.coerceIn(-64.0, 64.0), position.y.coerceIn(-64.0, 64.0), position.z.coerceIn(-64.0, 64.0)),
        Vec3(wrap(rotation.x), wrap(rotation.y), wrap(rotation.z)),
        scale.coerceIn(0.05, 8.0),
    )

    private fun wrap(d: Double): Double { var r = d % 360.0; if (r > 180) r -= 360; if (r < -180) r += 360; return r }
}

enum class HoldView(val labelFr: String) { FIRST_PERSON("1re personne"), THIRD_PERSON("3e personne") }

/** Réglages de départ pour les formes courantes ; on les ajuste ensuite à l'œil. */
enum class HoldPreset(val labelFr: String, val firstPerson: HoldTransform, val thirdPerson: HoldTransform) {
    SWORD("Épée / outil", HoldTransform(Vec3(0.0, 2.0, -2.0), Vec3(-20.0, 0.0, 0.0), 1.0), HoldTransform(Vec3(0.0, -2.0, -1.0), Vec3(-45.0, 0.0, 0.0), 1.0)),
    STAFF("Bâton / lance", HoldTransform(Vec3(0.0, -2.0, -2.0), Vec3(-10.0, 0.0, 0.0), 1.0), HoldTransform(Vec3(0.0, -6.0, 0.0), Vec3(-80.0, 0.0, 0.0), 1.0)),
    HEAVY("Marteau / gros objet", HoldTransform(Vec3(0.0, 0.0, -3.0), Vec3(-30.0, 0.0, 0.0), 0.9), HoldTransform(Vec3(0.0, -3.0, -1.0), Vec3(-60.0, 0.0, 0.0), 0.9)),
    SHIELD("Bouclier / plat", HoldTransform(Vec3(2.0, 2.0, -4.0), Vec3(0.0, 90.0, 0.0), 1.0), HoldTransform(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 90.0, 0.0), 1.0)),
    SMALL("Petit objet", HoldTransform(Vec3(0.0, 3.0, -2.0), Vec3.ZERO, 0.6), HoldTransform(Vec3(0.0, -1.0, -1.0), Vec3(-90.0, 0.0, 0.0), 0.6)),
}

/**
 * Objet 3D tenu en main (« attachable » avec un modèle 3D).
 *
 * Format suivi (wiki.bedrock.dev, « Attachables » ; Microsoft Learn, attachables) :
 * - le premier os du modèle est lié à la main par `"binding": "q.item_slot_to_bone_name(c.item_slot)"`
 *   (format de géométrie 1.16.0 ou plus) ;
 * - deux animations placent l'objet, l'une pour la 1re personne (`c.is_first_person`), l'autre pour la 3e ;
 * - matériaux `entity_alphatest` / `entity_alphatest_glint`, contrôleur `controller.render.item_default`.
 * Les valeurs de placement sont écrites telles que l'aperçu les montre ; l'aperçu reste une approximation
 * de la pose de Minecraft et doit être confirmé par un essai en jeu.
 */
object HeldItem {
    const val BINDING = "q.item_slot_to_bone_name(c.item_slot)"
    const val ROOT_BONE = "bms_hand"

    data class ItemRef(val id: String, val path: String)

    data class Spec(
        val itemId: String,
        val geometryPath: String,
        val texture: String,
        val firstPerson: HoldTransform,
        val thirdPerson: HoldTransform,
    )

    fun shortName(itemId: String) = itemId.substringAfter(':').replace('.', '_')
    fun attachablePath(itemId: String) = "$RP/attachables/${shortName(itemId)}.held.json"
    fun animationPath(itemId: String) = "$RP/animations/${shortName(itemId)}.held.animation.json"
    fun animationName(itemId: String, view: HoldView) =
        "animation.${itemId.substringBefore(':')}.${shortName(itemId)}.hold_" + if (view == HoldView.FIRST_PERSON) "first_person" else "third_person"

    /** Objets du behavior pack. */
    fun items(files: ProjectFiles): List<ItemRef> {
        if (!files.exists("$BP/items")) return emptyList()
        return files.walk("$BP/items").filter { it.endsWith(".json") }.mapNotNull { p ->
            val v = runCatching { JsonParser.parse(files.readText(p)).valueOrNull() }.getOrNull() ?: return@mapNotNull null
            v.path("minecraft:item", "description", "identifier")?.asString?.let { ItemRef(it, p) }
        }.sortedBy { it.id }
    }

    /**
     * Un seul os racine, lié à la main. Si le modèle a plusieurs racines, un os [ROOT_BONE] les regroupe ;
     * le pivot de la racine est mis à l'origine pour que les réglages de placement se lisent simplement.
     */
    fun prepare(model: GeoModel): GeoModel {
        val roots = model.bones.filter { it.parent == null || model.bone(it.parent) == null }
        val single = roots.singleOrNull()
        return if (single != null && single.pivot == Vec3.ZERO) {
            model.copy(bones = model.bones.map { if (it.name == single.name) withBinding(it) else it })
        } else {
            val name = generateSequence(ROOT_BONE) { it + "_" }.first { n -> model.bone(n) == null }
            val rootNames = roots.map { it.name }.toSet()
            model.copy(bones = listOf(withBinding(Bone(name))) + model.bones.map { if (it.name in rootNames) it.copy(parent = name) else it })
        }
    }

    private fun withBinding(b: Bone) = b.copy(extra = JsonObject(b.extra.entries.associate { it.key to it.value } + ("binding" to JsonString(BINDING))))

    fun rootBone(model: GeoModel): String = model.bones.firstOrNull { it.extra.str("binding") != null }?.name
        ?: model.bones.firstOrNull { it.parent == null }?.name ?: ROOT_BONE

    fun generate(files: ProjectFiles, spec: Spec): GeneratedContent {
        val geo = GeoCodec.parse(files.readText(spec.geometryPath))
        val model = geo.models.firstOrNull() ?: throw UserFacingException("Le fichier de modèle est vide.")
        val prepared = prepare(model)
        val root = rootBone(prepared)
        val fv = if (geo.formatVersion < "1.16.0" || geo.legacy) "1.16.0" else geo.formatVersion
        val fp = spec.firstPerson.clamped(); val tp = spec.thirdPerson.clamped()
        val anims = AnimationFile("1.8.0", listOf(
            hold(animationName(spec.itemId, HoldView.FIRST_PERSON), root, fp),
            hold(animationName(spec.itemId, HoldView.THIRD_PERSON), root, tp),
        ))
        return GeneratedContent().apply {
            text(spec.geometryPath, GeoCodec.write(GeoFile(fv, listOf(prepared) + geo.models.drop(1), legacy = false)))
            text(animationPath(spec.itemId), AnimationCodec.write(anims))
            text(attachablePath(spec.itemId), JsonWriter.write(attachable(spec.itemId, prepared.identifier, spec.texture)) + "\n")
        }
    }

    private fun hold(name: String, bone: String, t: HoldTransform) = Animation(name, loop = true, length = 1.0, bones = mapOf(bone to BoneTrack(mapOf(
        Channel.POSITION to listOf(Keyframe(0.0, t.position)),
        Channel.ROTATION to listOf(Keyframe(0.0, t.rotation)),
        Channel.SCALE to listOf(Keyframe(0.0, Vec3(t.scale, t.scale, t.scale))),
    ))))

    fun attachable(itemId: String, geometryId: String, texture: String) = obj(
        "format_version" to "1.10.0",
        "minecraft:attachable" to obj(
            "description" to obj(
                "identifier" to itemId,
                "materials" to obj("default" to "entity_alphatest", "enchanted" to "entity_alphatest_glint"),
                "textures" to obj("default" to texture, "enchanted" to "textures/misc/enchanted_item_glint"),
                "geometry" to obj("default" to geometryId),
                "animations" to obj(
                    "hold_first_person" to animationName(itemId, HoldView.FIRST_PERSON),
                    "hold_third_person" to animationName(itemId, HoldView.THIRD_PERSON),
                ),
                "scripts" to obj(
                    "pre_animation" to listOf("v.main_hand = c.item_slot == 'main_hand';"),
                    "animate" to listOf(
                        obj("hold_first_person" to "v.main_hand && c.is_first_person"),
                        obj("hold_third_person" to "v.main_hand && !c.is_first_person"),
                    ),
                ),
                "render_controllers" to listOf("controller.render.item_default"),
            ),
        ),
    )

    /** Relit un placement déjà enregistré (pour le modifier), ou null. */
    fun read(files: ProjectFiles, itemId: String): Pair<HoldTransform, HoldTransform>? {
        val p = animationPath(itemId)
        if (!files.exists(p)) return null
        val f = runCatching { AnimationCodec.parse(files.readText(p)) }.getOrNull() ?: return null
        fun t(view: HoldView): HoldTransform? {
            val a = f.animations.firstOrNull { it.name == animationName(itemId, view) } ?: return null
            val pose = a.sample(0.0).values.firstOrNull() ?: return null
            return HoldTransform(pose.position, pose.rotation, pose.scale.x)
        }
        return (t(HoldView.FIRST_PERSON) ?: return null) to (t(HoldView.THIRD_PERSON) ?: return null)
    }

    /** Texture déclarée pour ce modèle par une entité ou un attachable du projet (chemin sans extension). */
    fun textureFor(files: ProjectFiles, geometryId: String): String? {
        for (p in files.walk(RP).filter { it.endsWith(".json") && (it.contains("/entity/") || it.contains("/attachables/")) }) {
            val v = runCatching { JsonParser.parse(files.readText(p)).valueOrNull() }.getOrNull() ?: continue
            val desc = v.path("minecraft:client_entity", "description") ?: v.path("minecraft:attachable", "description") ?: continue
            val geos = (desc.path("geometry") as? JsonObject)?.entries?.mapNotNull { it.value.asString } ?: continue
            if (geometryId in geos) return (desc.path("textures") as? JsonObject)?.entries?.firstOrNull()?.value?.asString
        }
        return null
    }

    // ---- Aperçu ----

    /** Personnage de démonstration (proportions humanoïdes classiques, 64×64), dessiné en couleurs unies. */
    val mannequin: GeoModel = GeoModel("geometry.bms.mannequin", 64, 64, listOf(
        Bone("root"),
        Bone("body", "root", Vec3(0.0, 24.0, 0.0), cubes = listOf(Cube(Vec3(-4.0, 12.0, -2.0), Vec3(8.0, 12.0, 4.0)))),
        Bone("head", "body", Vec3(0.0, 24.0, 0.0), cubes = listOf(Cube(Vec3(-4.0, 24.0, -4.0), Vec3(8.0, 8.0, 8.0)))),
        Bone("rightArm", "body", Vec3(-5.0, 22.0, 0.0), cubes = listOf(Cube(Vec3(-8.0, 12.0, -2.0), Vec3(4.0, 12.0, 4.0)))),
        Bone("rightItem", "rightArm", Vec3(-6.0, 15.0, 1.0)),
        Bone("leftArm", "body", Vec3(5.0, 22.0, 0.0), cubes = listOf(Cube(Vec3(4.0, 12.0, -2.0), Vec3(4.0, 12.0, 4.0)))),
        Bone("rightLeg", "root", Vec3(-1.9, 12.0, 0.0), cubes = listOf(Cube(Vec3(-3.9, 0.0, -2.0), Vec3(4.0, 12.0, 4.0)))),
        Bone("leftLeg", "root", Vec3(1.9, 12.0, 0.0), cubes = listOf(Cube(Vec3(-0.1, 0.0, -2.0), Vec3(4.0, 12.0, 4.0)))),
    ))

    /** Bras tendu vers l'avant (3e personne) ou bras seul devant la caméra (1re personne). */
    fun armPose(view: HoldView): Map<String, BonePose> = when (view) {
        HoldView.THIRD_PERSON -> mapOf("rightArm" to BonePose(rotation = Vec3(-20.0, 0.0, 0.0)))
        HoldView.FIRST_PERSON -> mapOf("rightArm" to BonePose(rotation = Vec3(-80.0, -10.0, 0.0)))
    }

    /** Œil du personnage et direction du regard (vers −Z, l'avant des modèles). */
    val EYE = Vec3(-1.0, 25.0, 4.0)

    /** Matrice qui place l'origine du modèle de l'objet dans la main du personnage. */
    fun handMatrix(view: HoldView): Mat4 {
        val mats = MeshBuilder.boneMatrices(mannequin, armPose(view))
        return mats.getValue("rightItem") * Mat4.translate(mannequin.bone("rightItem")!!.pivot)
    }

    /**
     * Faces de l'aperçu : personnage (bone = -1 ; en 1re personne seulement le bras) puis objet placé dans la main.
     * Les faces de l'objet gardent leurs indices d'os et leurs UV pour être texturées.
     */
    fun preview(item: GeoModel, view: HoldView, t: HoldTransform): Pair<List<MeshFace>, List<MeshFace>> {
        val bodyFaces = MeshBuilder.build(mannequin, armPose(view)).filter { view == HoldView.THIRD_PERSON || mannequin.bones[it.boneIndex].name == "rightArm" }
        val prepared = prepare(item)
        val root = rootBone(prepared)
        val hand = handMatrix(view)
        val itemFaces = MeshBuilder.build(prepared, mapOf(root to t.clamped().pose())).map { MeshBuilder.transform(it, hand) }
        return bodyFaces to itemFaces
    }

    /** Convertit un déplacement vu à l'écran (repère du monde) en déplacement dans le repère de la main. */
    fun worldToHand(view: HoldView, delta: Vec3): Vec3 = handMatrix(view).inverseRotate(delta)
}
