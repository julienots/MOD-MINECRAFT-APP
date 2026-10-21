package com.bedrockmodstudio.core.model3d

import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonNumber
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.jsonOf
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.storage.UserFacingException
import java.util.Locale

enum class Channel(val key: String, val labelFr: String) { ROTATION("rotation", "Rotation"), POSITION("position", "Position"), SCALE("scale", "Échelle") }

/** Image clé numérique. Les canaux exprimés en Molang (texte) sont conservés sans être modifiables ici. */
data class Keyframe(val time: Double, val value: Vec3)

data class BoneTrack(
    val keyframes: Map<Channel, List<Keyframe>> = emptyMap(),
    /** Canaux non numériques (expressions Molang, formes « pre/post »…), réécrits tels quels. */
    val raw: Map<Channel, JsonValue> = emptyMap(),
)

data class Animation(
    val name: String,
    val loop: Boolean = false,
    val length: Double = 1.0,
    val bones: Map<String, BoneTrack> = emptyMap(),
    val extra: JsonObject = JsonObject(),
) {
    /** Pose de chaque os à l'instant [t] (interpolation linéaire entre images clés). */
    fun sample(t: Double): Map<String, BonePose> = bones.mapValues { (_, track) ->
        fun v(c: Channel, default: Vec3): Vec3 {
            val kfs = track.keyframes[c]?.sortedBy { it.time } ?: return default
            if (kfs.isEmpty()) return default
            if (t <= kfs.first().time) return kfs.first().value
            if (t >= kfs.last().time) return kfs.last().value
            val i = kfs.indexOfLast { it.time <= t }
            val a = kfs[i]; val b = kfs[i + 1]
            val k = if (b.time == a.time) 0.0 else (t - a.time) / (b.time - a.time)
            return a.value + (b.value - a.value) * k
        }
        BonePose(v(Channel.ROTATION, Vec3.ZERO), v(Channel.POSITION, Vec3.ZERO), v(Channel.SCALE, Vec3(1.0, 1.0, 1.0)))
    }

    fun withKeyframe(bone: String, channel: Channel, kf: Keyframe): Animation {
        val track = bones[bone] ?: BoneTrack()
        val list = (track.keyframes[channel] ?: emptyList()).filter { Math.abs(it.time - kf.time) > 1e-4 } + kf
        return copy(bones = bones + (bone to track.copy(keyframes = track.keyframes + (channel to list.sortedBy { it.time }), raw = track.raw - channel)))
    }

    fun withoutKeyframe(bone: String, channel: Channel, time: Double): Animation {
        val track = bones[bone] ?: return this
        val list = (track.keyframes[channel] ?: emptyList()).filter { Math.abs(it.time - time) > 1e-4 }
        return copy(bones = bones + (bone to track.copy(keyframes = track.keyframes + (channel to list))))
    }
}

data class AnimationFile(val formatVersion: String, val animations: List<Animation>)

object AnimationCodec {
    private val reserved = setOf("loop", "animation_length", "bones")

    fun parse(text: String): AnimationFile {
        val root = (JsonParser.parse(text) as? JsonParseResult.Success)?.value as? JsonObject
            ?: throw UserFacingException("Ce fichier d'animation n'est pas un JSON valide.")
        val anims = root.obj("animations") ?: throw UserFacingException("Section « animations » absente.")
        return AnimationFile(root.str("format_version") ?: "1.8.0", anims.entries.mapNotNull { (name, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val loop = o["loop"]?.asBoolean ?: (o.str("loop") == "hold_on_last_frame")
            Animation(
                name = name,
                loop = loop,
                length = o["animation_length"]?.asDouble ?: 0.0,
                bones = (o.obj("bones")?.entries ?: emptySet()).associate { (bone, bv) -> bone to parseTrack(bv as? JsonObject) },
                extra = JsonObject(o.entries.filter { it.key !in reserved }.associate { it.key to it.value }),
            ).let { a -> if (a.length <= 0.0) a.copy(length = a.bones.values.flatMap { t -> t.keyframes.values.flatten() }.maxOfOrNull { it.time } ?: 1.0) else a }
        })
    }

    private fun numericVec(v: JsonValue?): Vec3? {
        return when (v) {
            is JsonArray -> if (v.size == 3 && v.items.all { it is JsonNumber }) Vec3(v[0].asDouble!!, v[1].asDouble!!, v[2].asDouble!!) else null
            is JsonNumber -> v.toDouble().let { Vec3(it, it, it) }
            is JsonObject -> numericVec(v["post"] ?: v["pre"]).takeIf { v.containsKey("post") || v.containsKey("pre") }
            else -> null
        }
    }

    private fun parseTrack(o: JsonObject?): BoneTrack {
        o ?: return BoneTrack()
        val kfs = HashMap<Channel, List<Keyframe>>()
        val raw = HashMap<Channel, JsonValue>()
        for (c in Channel.entries) {
            val v = o[c.key] ?: continue
            val constant = numericVec(v)
            if (constant != null && v !is JsonObject) { kfs[c] = listOf(Keyframe(0.0, constant)); continue }
            if (v is JsonObject && v.keys.all { it.toDoubleOrNull() != null }) {
                val list = v.entries.map { (t, kv) -> numericVec(kv)?.let { Keyframe(t.toDouble(), it) } }
                if (list.all { it != null }) { kfs[c] = list.filterNotNull().sortedBy { it.time }; continue }
            }
            raw[c] = v // expression Molang ou forme non gérée : conservée
        }
        return BoneTrack(kfs, raw)
    }

    private fun t(d: Double) = String.format(Locale.ROOT, "%.4f", d).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
    private fun vec(v: Vec3) = JsonArray(listOf(JsonNumber(round(v.x)), JsonNumber(round(v.y)), JsonNumber(round(v.z))))
    private fun round(d: Double) = Math.round(d * 1000) / 1000.0

    fun write(file: AnimationFile): String {
        val anims = JsonObject()
        for (a in file.animations) {
            val o = JsonObject()
            o["loop"] = jsonOf(a.loop)
            o["animation_length"] = JsonNumber(round(a.length))
            a.extra.entries.forEach { o[it.key] = it.value }
            val bones = JsonObject()
            for ((name, track) in a.bones) {
                val bo = JsonObject()
                for (c in Channel.entries) {
                    track.raw[c]?.let { bo[c.key] = it }
                    val kfs = track.keyframes[c] ?: continue
                    if (kfs.isEmpty()) continue
                    bo[c.key] = if (kfs.size == 1 && kfs[0].time == 0.0) vec(kfs[0].value)
                    else JsonObject(kfs.associate { t(it.time) to vec(it.value) as JsonValue })
                }
                if (bo.size > 0) bones[name] = bo
            }
            o["bones"] = bones
            anims[a.name] = o
        }
        return JsonWriter.write(obj("format_version" to file.formatVersion, "animations" to anims)) + "\n"
    }

    /** Modèles d'animation prêts à l'emploi, appliqués aux os choisis. */
    fun template(kind: String, name: String, bones: List<String>): Animation {
        fun a(vararg kf: Pair<Double, Vec3>) = kf.map { Keyframe(it.first, it.second) }
        val first = bones.firstOrNull() ?: "body"
        return when (kind) {
            "walk" -> Animation(name, loop = true, length = 1.0, bones = bones.mapIndexed { i, b ->
                val sign = if (i % 2 == 0) 1.0 else -1.0
                b to BoneTrack(mapOf(Channel.ROTATION to a(0.0 to Vec3(30 * sign, 0.0, 0.0), 0.5 to Vec3(-30 * sign, 0.0, 0.0), 1.0 to Vec3(30 * sign, 0.0, 0.0))))
            }.toMap())
            "attack" -> Animation(name, loop = false, length = 0.5, bones = mapOf(first to BoneTrack(mapOf(Channel.ROTATION to
                a(0.0 to Vec3.ZERO, 0.15 to Vec3(-60.0, 0.0, 0.0), 0.3 to Vec3(20.0, 0.0, 0.0), 0.5 to Vec3.ZERO)))))
            "spawn" -> Animation(name, loop = false, length = 1.0, bones = mapOf(first to BoneTrack(mapOf(Channel.SCALE to
                a(0.0 to Vec3(0.1, 0.1, 0.1), 0.7 to Vec3(1.15, 1.15, 1.15), 1.0 to Vec3(1.0, 1.0, 1.0))))))
            "death" -> Animation(name, loop = false, length = 1.0, bones = mapOf(first to BoneTrack(mapOf(Channel.ROTATION to
                a(0.0 to Vec3.ZERO, 1.0 to Vec3(0.0, 0.0, 90.0)), Channel.POSITION to a(0.0 to Vec3.ZERO, 1.0 to Vec3(0.0, -4.0, 0.0))))))
            "idle" -> Animation(name, loop = true, length = 2.0, bones = mapOf(first to BoneTrack(mapOf(Channel.POSITION to
                a(0.0 to Vec3.ZERO, 1.0 to Vec3(0.0, 0.5, 0.0), 2.0 to Vec3.ZERO)))))
            "transform" -> Animation(name, loop = false, length = 1.5, bones = mapOf(first to BoneTrack(mapOf(
                Channel.ROTATION to a(0.0 to Vec3.ZERO, 1.5 to Vec3(0.0, 720.0, 0.0)),
                Channel.SCALE to a(0.0 to Vec3(1.0, 1.0, 1.0), 0.75 to Vec3(0.5, 1.5, 0.5), 1.5 to Vec3(1.3, 1.3, 1.3))))))
            else -> Animation(name, loop = true, length = 1.0)
        }
    }

    val TEMPLATES = listOf("walk" to "Marche", "attack" to "Attaque", "idle" to "Respiration", "spawn" to "Apparition", "death" to "Mort", "transform" to "Transformation")
}
