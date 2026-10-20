package com.bedrockmodstudio.core.json

/**
 * Modèle JSON minimal, sans dépendance, qui conserve l'ordre des clés.
 * L'ordre compte : les fichiers Bedrock sont lus par des humains et doivent rester stables d'un export à l'autre.
 */
sealed class JsonValue {
    /** Position (1-based) de la valeur dans le texte source, si elle provient du parseur. */
    var line: Int = 0
        internal set
    var column: Int = 0
        internal set

    val asObject: JsonObject? get() = this as? JsonObject
    val asArray: JsonArray? get() = this as? JsonArray
    val asString: String? get() = (this as? JsonString)?.value
    val asDouble: Double? get() = (this as? JsonNumber)?.toDouble()
    val asInt: Int? get() = (this as? JsonNumber)?.toIntOrNull()
    val asBoolean: Boolean? get() = (this as? JsonBool)?.value
}

class JsonObject(entries: Map<String, JsonValue> = emptyMap()) : JsonValue() {
    private val map = LinkedHashMap<String, JsonValue>(entries)

    val entries: Set<Map.Entry<String, JsonValue>> get() = map.entries
    val keys: Set<String> get() = map.keys
    val size: Int get() = map.size

    operator fun get(key: String): JsonValue? = map[key]
    operator fun set(key: String, value: JsonValue) { map[key] = value }
    fun remove(key: String): JsonValue? = map.remove(key)
    fun containsKey(key: String) = map.containsKey(key)

    fun obj(key: String): JsonObject? = map[key] as? JsonObject
    fun arr(key: String): JsonArray? = map[key] as? JsonArray
    fun str(key: String): String? = (map[key] as? JsonString)?.value

    override fun equals(other: Any?) = other is JsonObject && other.map == map
    override fun hashCode() = map.hashCode()
    override fun toString() = JsonWriter.write(this)
}

class JsonArray(items: List<JsonValue> = emptyList()) : JsonValue() {
    private val list = ArrayList(items)
    val items: List<JsonValue> get() = list
    val size: Int get() = list.size
    operator fun get(index: Int): JsonValue = list[index]
    fun add(value: JsonValue) { list.add(value) }

    override fun equals(other: Any?) = other is JsonArray && other.list == list
    override fun hashCode() = list.hashCode()
    override fun toString() = JsonWriter.write(this)
}

class JsonString(val value: String) : JsonValue() {
    override fun equals(other: Any?) = other is JsonString && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = JsonWriter.write(this)
}

/** Le texte d'origine est conservé pour ne pas altérer les nombres à la réécriture. */
class JsonNumber(val raw: String) : JsonValue() {
    constructor(value: Int) : this(value.toString())
    constructor(value: Long) : this(value.toString())
    constructor(value: Double) : this(formatDouble(value))

    fun toDouble(): Double = raw.toDouble()
    fun toIntOrNull(): Int? = raw.toIntOrNull() ?: raw.toDoubleOrNull()?.let { d ->
        if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    override fun equals(other: Any?) = other is JsonNumber && other.toDouble() == toDouble()
    override fun hashCode() = toDouble().hashCode()
    override fun toString() = raw

    companion object {
        fun formatDouble(d: Double): String {
            require(!d.isNaN() && !d.isInfinite()) { "Nombre JSON invalide : $d" }
            return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
    }
}

class JsonBool(val value: Boolean) : JsonValue() {
    override fun equals(other: Any?) = other is JsonBool && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = value.toString()
}

object JsonNull : JsonValue() {
    override fun toString() = "null"
}

// ---- Construction concise des fichiers générés ----

fun jsonOf(value: Any?): JsonValue = when (value) {
    null -> JsonNull
    is JsonValue -> value
    is String -> JsonString(value)
    is Boolean -> JsonBool(value)
    is Int -> JsonNumber(value)
    is Long -> JsonNumber(value)
    is Float -> JsonNumber(value.toDouble())
    is Double -> JsonNumber(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is Iterable<*> -> JsonArray(value.map { jsonOf(it) })
    is Array<*> -> JsonArray(value.map { jsonOf(it) })
    is IntArray -> JsonArray(value.map { JsonNumber(it) })
    is DoubleArray -> JsonArray(value.map { JsonNumber(it) })
    else -> throw IllegalArgumentException("Type non convertible en JSON : ${value::class.simpleName}")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { it.first to jsonOf(it.second) })

fun arr(vararg items: Any?): JsonArray = JsonArray(items.map { jsonOf(it) })

/** Accès par chemin : `value.path("minecraft:entity", "description", "identifier")`. */
fun JsonValue?.path(vararg keys: String): JsonValue? {
    var cur: JsonValue? = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}
