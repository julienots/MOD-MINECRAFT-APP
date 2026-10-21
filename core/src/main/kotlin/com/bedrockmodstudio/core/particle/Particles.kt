package com.bedrockmodstudio.core.particle

import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.image.PixelImage
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonNumber
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.arr
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model3d.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class EmitterShape(val labelFr: String, val component: String) {
    POINT("Point", "minecraft:emitter_shape_point"),
    SPHERE("Sphère", "minecraft:emitter_shape_sphere"),
    BOX("Boîte", "minecraft:emitter_shape_box"),
}

enum class EmitDirection(val labelFr: String) { OUTWARDS("Vers l'extérieur"), INWARDS("Vers l'intérieur"), UP("Vers le haut"), DOWN("Vers le bas") }

/**
 * Sous-ensemble éditable d'un effet de particules Bedrock (fichiers .json de resource_pack/particles).
 * Composants et syntaxe relevés dans Microsoft Learn (particlesreference) et les fichiers vanilla
 * de Mojang/bedrock-samples (basic_flame.json, heart.json).
 */
data class ParticleSpec(
    val identifier: String,
    val texture: String,
    val looping: Boolean = true,
    val activeTime: Double = 1.0,
    val burst: Boolean = false,
    /** Particules par seconde (émission continue) ou par salve. */
    val rate: Double = 20.0,
    val maxParticles: Int = 100,
    val shape: EmitterShape = EmitterShape.SPHERE,
    /** Rayon (sphère) ou demi-côté (boîte), en blocs. */
    val radius: Double = 0.3,
    val offsetY: Double = 0.0,
    val direction: EmitDirection = EmitDirection.OUTWARDS,
    val speed: Double = 2.0,
    val lifetime: Double = 1.0,
    val gravity: Double = 0.0,
    val drag: Double = 0.0,
    val size: Double = 0.1,
    val colorStart: Int = 0xFFFFFFFF.toInt(),
    val colorEnd: Int = 0xFFFFFFFF.toInt(),
) {
    fun validate(): List<String> = buildList {
        if (!Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$").matches(identifier)) add("Identifiant de particule invalide « $identifier ».")
        if (activeTime !in 0.05..60.0) add("Durée d'émission : entre 0,05 et 60 s.")
        if (rate !in 0.1..1000.0) add("Quantité : entre 0,1 et 1000.")
        if (maxParticles !in 1..2000) add("Nombre maximal de particules : entre 1 et 2000.")
        if (lifetime !in 0.05..30.0) add("Durée de vie : entre 0,05 et 30 s.")
        if (this@ParticleSpec.size !in 0.01..5.0) add("Taille : entre 0,01 et 5 blocs.")
        if (radius !in 0.0..16.0) add("Rayon : entre 0 et 16 blocs.")
    }
}

/** Résultat de lecture : les paramètres reconnus, ou la liste de ce que l'éditeur ne sait pas reprendre sans perte. */
class ParticleReadResult(val spec: ParticleSpec?, val unsupported: List<String>)

object ParticleCodec {
    const val FORMAT = "1.26.10"
    private val EDITABLE = setOf(
        "minecraft:emitter_lifetime_looping", "minecraft:emitter_lifetime_once", "minecraft:emitter_rate_steady", "minecraft:emitter_rate_instant",
        "minecraft:emitter_shape_point", "minecraft:emitter_shape_sphere", "minecraft:emitter_shape_box", "minecraft:particle_initial_speed",
        "minecraft:particle_lifetime_expression", "minecraft:particle_motion_dynamic", "minecraft:particle_appearance_billboard",
        "minecraft:particle_appearance_tinting",
    )

    private fun rgba(c: Int) = listOf((c shr 16 and 0xFF) / 255.0, (c shr 8 and 0xFF) / 255.0, (c and 0xFF) / 255.0, (c ushr 24) / 255.0).map { Math.round(it * 1000) / 1000.0 }

    fun toJson(s: ParticleSpec): String {
        val dir: Any = when (s.direction) {
            EmitDirection.OUTWARDS -> if (s.shape == EmitterShape.POINT) arr(0, 1, 0) else "outwards"
            EmitDirection.INWARDS -> if (s.shape == EmitterShape.POINT) arr(0, -1, 0) else "inwards"
            EmitDirection.UP -> arr(0, 1, 0)
            EmitDirection.DOWN -> arr(0, -1, 0)
        }
        val offset = arr(0, s.offsetY, 0)
        val shape = when (s.shape) {
            EmitterShape.POINT -> obj("offset" to offset, "direction" to dir)
            EmitterShape.SPHERE -> obj("offset" to offset, "radius" to s.radius, "direction" to dir)
            EmitterShape.BOX -> obj("offset" to offset, "half_dimensions" to arr(s.radius, s.radius, s.radius), "direction" to dir)
        }
        val color: Any = if (s.colorStart == s.colorEnd) rgba(s.colorStart) else obj(
            "interpolant" to "v.particle_age / v.particle_lifetime",
            "gradient" to obj("0.0" to rgba(s.colorStart), "1.0" to rgba(s.colorEnd)),
        )
        val components = obj(
            (if (s.looping) "minecraft:emitter_lifetime_looping" else "minecraft:emitter_lifetime_once") to obj("active_time" to s.activeTime),
            (if (s.burst) "minecraft:emitter_rate_instant" to obj("num_particles" to s.rate)
            else "minecraft:emitter_rate_steady" to obj("spawn_rate" to s.rate, "max_particles" to s.maxParticles)),
            s.shape.component to shape,
            "minecraft:particle_initial_speed" to s.speed,
            "minecraft:particle_lifetime_expression" to obj("max_lifetime" to s.lifetime),
            "minecraft:particle_motion_dynamic" to obj("linear_acceleration" to arr(0, 0.0 - s.gravity, 0), "linear_drag_coefficient" to s.drag),
            "minecraft:particle_appearance_billboard" to obj(
                "size" to arr(s.size, s.size), "facing_camera_mode" to "lookat_xyz",
                "uv" to obj("texture_width" to 8, "texture_height" to 8, "uv" to arr(0, 0), "uv_size" to arr(8, 8)),
            ),
            "minecraft:particle_appearance_tinting" to obj("color" to color),
        )
        return JsonWriter.write(obj(
            "format_version" to FORMAT,
            "particle_effect" to obj(
                "description" to obj("identifier" to s.identifier, "basic_render_parameters" to obj("material" to "particles_alpha", "texture" to s.texture)),
                "components" to components,
            ),
        ))
    }

    private fun num(v: JsonValue?): Double? = (v as? JsonNumber)?.toDouble()
    private fun color(v: JsonValue?): Int? {
        val a = v as? JsonArray ?: return null
        val c = a.items.map { num(it) ?: return null }
        if (c.size !in 3..4) return null
        fun ch(x: Double) = (x.coerceIn(0.0, 1.0) * 255).toInt()
        return (ch(c.getOrElse(3) { 1.0 }) shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
    }

    /** Lit un fichier ; toute valeur en Molang ou tout composant inconnu est signalé (l'éditeur passe alors en aperçu seul). */
    fun read(text: String): ParticleReadResult {
        val root = runCatching { JsonParser.parseOrThrow(text) }.getOrNull() as? JsonObject
            ?: return ParticleReadResult(null, listOf("Le fichier contient une erreur JSON."))
        val pe = root.obj("particle_effect") ?: return ParticleReadResult(null, listOf("Ce fichier n'est pas un effet de particules."))
        val desc = pe.obj("description")
        val id = desc?.str("identifier") ?: return ParticleReadResult(null, listOf("Identifiant absent."))
        val c = pe.obj("components") ?: JsonObject()
        val issues = mutableListOf<String>()
        c.keys.filter { it !in EDITABLE }.forEach { issues += "composant $it" }
        if (desc.obj("basic_render_parameters")?.str("material") != "particles_alpha") issues += "matériau différent de particles_alpha"
        fun n(v: JsonValue?, what: String, def: Double): Double = if (v == null) def else num(v) ?: def.also { issues += "$what (expression Molang)" }
        val looping = c.containsKey("minecraft:emitter_lifetime_looping")
        val life = c.obj(if (looping) "minecraft:emitter_lifetime_looping" else "minecraft:emitter_lifetime_once")
        if (life?.containsKey("sleep_time") == true) issues += "pause entre deux boucles"
        val burst = c.containsKey("minecraft:emitter_rate_instant")
        val rateObj = c.obj(if (burst) "minecraft:emitter_rate_instant" else "minecraft:emitter_rate_steady")
        val shape = EmitterShape.entries.firstOrNull { c.containsKey(it.component) } ?: EmitterShape.POINT
        val sh = c.obj(shape.component) ?: JsonObject()
        if (sh.containsKey("surface_only")) issues += "émission en surface seulement"
        val offs = (sh["offset"] as? JsonArray)?.items?.map { num(it) }
        if (offs != null && (offs.any { it == null } || offs[0] != 0.0 || offs[2] != 0.0)) issues += "décalage horizontal ou en Molang"
        val dirV = sh["direction"]
        val direction = when {
            dirV == null || (dirV as? JsonString)?.value == "outwards" -> if (shape == EmitterShape.POINT && dirV == null) EmitDirection.UP else EmitDirection.OUTWARDS
            (dirV as? JsonString)?.value == "inwards" -> EmitDirection.INWARDS
            dirV is JsonArray && dirV.items.map { num(it) } == listOf(0.0, 1.0, 0.0) -> EmitDirection.UP
            dirV is JsonArray && dirV.items.map { num(it) } == listOf(0.0, -1.0, 0.0) -> EmitDirection.DOWN
            else -> EmitDirection.OUTWARDS.also { issues += "direction personnalisée" }
        }
        val radius = when (shape) {
            EmitterShape.SPHERE -> n(sh["radius"], "rayon", 1.0)
            EmitterShape.BOX -> (sh["half_dimensions"] as? JsonArray)?.items?.map { num(it) }?.let { h ->
                if (h.any { it == null } || h.toSet().size != 1) issues += "boîte non cubique"
                h.firstOrNull() ?: 0.5
            } ?: 0.5
            EmitterShape.POINT -> 0.0
        }
        val motion = c.obj("minecraft:particle_motion_dynamic")
        val acc = (motion?.get("linear_acceleration") as? JsonArray)?.items?.map { num(it) }
        if (acc != null && (acc.any { it == null } || acc[0] != 0.0 || acc[2] != 0.0)) issues += "accélération horizontale ou en Molang"
        if (motion?.keys?.any { it.startsWith("rotation") } == true) issues += "rotation des particules"
        val bb = c.obj("minecraft:particle_appearance_billboard")
        val size = (bb?.get("size") as? JsonArray)?.items?.map { num(it) }
        if (size != null && (size.any { it == null } || size[0] != size[1])) issues += "taille non carrée ou en Molang"
        val uv = bb?.obj("uv")
        if (uv != null && (num(uv["texture_width"]) != 8.0 || num(uv["texture_height"]) != 8.0)) issues += "découpe de texture (UV) personnalisée"
        val col = c.obj("minecraft:particle_appearance_tinting")?.get("color")
        var c0 = 0xFFFFFFFF.toInt(); var c1 = c0
        when {
            col == null -> {}
            color(col) != null -> { c0 = color(col)!!; c1 = c0 }
            col is JsonObject && col.str("interpolant") == "v.particle_age / v.particle_lifetime" && col.obj("gradient")?.let { g ->
                val keys = g.keys.mapNotNull { it.toDoubleOrNull() }.sorted()
                keys == listOf(0.0, 1.0) && color(g[g.keys.first { it.toDoubleOrNull() == 0.0 }]) != null && color(g[g.keys.first { it.toDoubleOrNull() == 1.0 }]) != null
            } == true -> {
                val g = col.obj("gradient")!!
                c0 = color(g[g.keys.first { it.toDoubleOrNull() == 0.0 }])!!; c1 = color(g[g.keys.first { it.toDoubleOrNull() == 1.0 }])!!
            }
            else -> issues += "couleur en Molang, en hexadécimal ou dégradé complexe"
        }
        val spec = ParticleSpec(
            identifier = id,
            texture = desc.obj("basic_render_parameters")?.str("texture") ?: "textures/particle/particles",
            looping = looping, activeTime = n(life?.get("active_time"), "durée d'émission", 10.0),
            burst = burst,
            rate = n(rateObj?.get(if (burst) "num_particles" else "spawn_rate"), "quantité", if (burst) 10.0 else 1.0),
            maxParticles = if (burst) 100 else n(rateObj?.get("max_particles"), "nombre maximal", 50.0).toInt(),
            shape = shape, radius = radius, offsetY = offs?.getOrNull(1) ?: 0.0, direction = direction,
            speed = n(c["minecraft:particle_initial_speed"], "vitesse initiale", 0.0),
            lifetime = n(c.obj("minecraft:particle_lifetime_expression")?.get("max_lifetime"), "durée de vie", 1.0),
            gravity = 0.0 - (acc?.getOrNull(1) ?: 0.0),
            drag = n(motion?.get("linear_drag_coefficient"), "freinage", 0.0),
            size = size?.firstOrNull() ?: 0.1,
            colorStart = c0, colorEnd = c1,
        )
        if (c.containsKey("minecraft:particle_lifetime_expression") && c.obj("minecraft:particle_lifetime_expression")?.containsKey("expiration_expression") == true) issues += "condition d'expiration"
        return ParticleReadResult(spec, issues.distinct())
    }

    /** Texture originale : disque blanc aux bords adoucis, coloré en jeu par la teinte. */
    fun softDotTexture(): ByteArray {
        val img = PixelImage(8, 8)
        for (y in 0 until 8) for (x in 0 until 8) {
            val d = sqrt((x - 3.5) * (x - 3.5) + (y - 3.5) * (y - 3.5)) / 4.0
            val a = ((1.0 - d).coerceIn(0.0, 1.0) * 1.6).coerceAtMost(1.0)
            img[x, y] = ((a * 255).toInt() shl 24) or 0xFFFFFF
        }
        return img.toPng()
    }
}

enum class ParticlePreset(val labelFr: String) {
    SPARKS("Étincelles"), SMOKE("Fumée"), MAGIC("Magie"), HEARTS("Petits cœurs"), RAIN("Pluie");

    fun spec(id: String, texture: String): ParticleSpec = when (this) {
        SPARKS -> ParticleSpec(id, texture, rate = 40.0, shape = EmitterShape.SPHERE, radius = 0.1, speed = 4.0, lifetime = 0.7, gravity = 8.0, size = 0.06,
            colorStart = 0xFFFFE066.toInt(), colorEnd = 0x00FF5A1F)
        SMOKE -> ParticleSpec(id, texture, rate = 12.0, shape = EmitterShape.POINT, radius = 0.0, direction = EmitDirection.UP, speed = 0.8, lifetime = 2.5, gravity = -0.6, drag = 0.8,
            size = 0.25, colorStart = 0xCC8A8A8A.toInt(), colorEnd = 0x00505050)
        MAGIC -> ParticleSpec(id, texture, rate = 30.0, shape = EmitterShape.SPHERE, radius = 1.0, direction = EmitDirection.INWARDS, speed = 1.0, lifetime = 1.0, size = 0.08,
            colorStart = 0xFFB46CFF.toInt(), colorEnd = 0x0040E0FF)
        HEARTS -> ParticleSpec(id, texture, looping = false, activeTime = 0.2, burst = true, rate = 8.0, shape = EmitterShape.SPHERE, radius = 0.4, speed = 2.5, lifetime = 1.2,
            drag = 5.0, size = 0.15, colorStart = 0xFFFF6FA5.toInt(), colorEnd = 0x00FF6FA5)
        RAIN -> ParticleSpec(id, texture, rate = 60.0, maxParticles = 200, shape = EmitterShape.BOX, radius = 2.0, offsetY = 3.0, direction = EmitDirection.DOWN, speed = 6.0,
            lifetime = 0.6, size = 0.05, colorStart = 0xFF7FB2FF.toInt(), colorEnd = 0x807FB2FF.toInt())
    }
}

object ParticleGenerator {
    fun paths(shortId: String) = "$RP/particles/$shortId.json" to "$RP/textures/particle/$shortId.png"

    fun generate(ns: String, shortId: String, preset: ParticlePreset): GeneratedContent {
        val (json, png) = paths(shortId)
        val spec = preset.spec("$ns:$shortId", "textures/particle/$shortId")
        require(spec.validate().isEmpty()) { spec.validate().joinToString(" ") }
        return GeneratedContent().apply {
            text(json, ParticleCodec.toJson(spec))
            bytes(png, ParticleCodec.softDotTexture())
        }
    }
}

/** Particule simulée (coordonnées en blocs, y vers le haut). */
class SimParticle(var pos: Vec3, var vel: Vec3, var age: Double, val life: Double)

/**
 * Simulation approchée pour l'aperçu : même émission, mêmes vitesses, gravité et freinage que le fichier.
 * Le rendu exact (lumière, tri, texture) reste celui de Minecraft.
 */
class ParticleSimulator(val spec: ParticleSpec, seed: Int = 1) {
    private val rnd = Random(seed)
    val particles = ArrayList<SimParticle>()
    var time = 0.0; private set
    private var carry = 0.0
    private var burstDoneForCycle = -1

    private fun randomUnit(): Vec3 {
        val u = rnd.nextDouble() * 2 - 1; val t = rnd.nextDouble() * 2 * PI
        val r = sqrt(1 - u * u)
        return Vec3(r * cos(t), u, r * sin(t))
    }

    private fun spawn() {
        if (!spec.burst && particles.size >= spec.maxParticles) return
        val origin = Vec3(0.0, spec.offsetY, 0.0)
        val local = when (spec.shape) {
            EmitterShape.POINT -> Vec3.ZERO
            EmitterShape.SPHERE -> randomUnit() * (spec.radius * Math.cbrt(rnd.nextDouble()))
            EmitterShape.BOX -> Vec3(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1) * spec.radius
        }
        val dir = when (spec.direction) {
            EmitDirection.UP -> Vec3(0.0, 1.0, 0.0)
            EmitDirection.DOWN -> Vec3(0.0, -1.0, 0.0)
            EmitDirection.OUTWARDS, EmitDirection.INWARDS -> {
                val l = sqrt(local.x * local.x + local.y * local.y + local.z * local.z)
                val d = if (spec.shape == EmitterShape.POINT) Vec3(0.0, 1.0, 0.0) else if (l < 1e-6) randomUnit() else local * (1 / l)
                if (spec.direction == EmitDirection.INWARDS) d * -1.0 else d
            }
        }
        particles += SimParticle(origin + local, dir * spec.speed, 0.0, spec.lifetime)
    }

    /** Émetteur encore actif à l'instant [t] (une boucle recommence après sa durée). */
    private fun active(t: Double) = spec.looping || t < spec.activeTime

    fun step(dt: Double) {
        if (active(time)) {
            if (spec.burst) {
                val cycle = (time / spec.activeTime).toInt()
                if (cycle != burstDoneForCycle) { burstDoneForCycle = cycle; repeat(spec.rate.toInt()) { spawn() } }
            } else {
                carry += spec.rate * dt
                while (carry >= 1) { spawn(); carry -= 1 }
            }
        }
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) { it.remove(); continue }
            val acc = Vec3(0.0, -spec.gravity, 0.0) - p.vel * spec.drag
            p.vel = p.vel + acc * dt
            p.pos = p.pos + p.vel * dt
        }
        time += dt
    }

    /** Couleur ARGB d'une particule selon son âge (dégradé linéaire début → fin). */
    fun colorOf(p: SimParticle): Int {
        val t = (p.age / p.life).coerceIn(0.0, 1.0)
        fun mix(sh: Int) = (((spec.colorStart ushr sh) and 0xFF) * (1 - t) + ((spec.colorEnd ushr sh) and 0xFF) * t).toInt() shl sh
        return mix(24) or mix(16) or mix(8) or mix(0)
    }
}
