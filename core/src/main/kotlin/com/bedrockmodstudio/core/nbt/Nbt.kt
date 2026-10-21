package com.bedrockmodstudio.core.nbt

import com.bedrockmodstudio.core.storage.UserFacingException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NBT de Minecraft Bedrock : petit-boutiste (little-endian), sans compression (format des fichiers .mcstructure).
 * Source du format : https://wiki.bedrock.dev/nbt/mcstructure
 */
sealed class Nbt {
    abstract val id: kotlin.Int

    data class Byte(val v: kotlin.Byte) : Nbt() { override val id = 1 }
    data class Short(val v: kotlin.Short) : Nbt() { override val id = 2 }
    data class Int(val v: kotlin.Int) : Nbt() { override val id = 3 }
    data class Long(val v: kotlin.Long) : Nbt() { override val id = 4 }
    data class Float(val v: kotlin.Float) : Nbt() { override val id = 5 }
    data class Double(val v: kotlin.Double) : Nbt() { override val id = 6 }
    class ByteArray(val v: kotlin.ByteArray) : Nbt() {
        override val id = 7
        override fun equals(other: Any?) = other is ByteArray && other.v.contentEquals(v)
        override fun hashCode() = v.contentHashCode()
    }
    data class Str(val v: String) : Nbt() { override val id = 8 }
    /** [elementType] est conservé même pour une liste vide (Minecraft y est sensible). */
    data class ListTag(val elementType: kotlin.Int, val items: List<Nbt>) : Nbt() { override val id = 9 }
    data class Compound(val map: LinkedHashMap<String, Nbt> = LinkedHashMap()) : Nbt() {
        override val id = 10
        operator fun get(k: String) = map[k]
        operator fun set(k: String, v: Nbt) { map[k] = v }
    }
    class IntArray(val v: kotlin.IntArray) : Nbt() {
        override val id = 11
        override fun equals(other: Any?) = other is IntArray && other.v.contentEquals(v)
        override fun hashCode() = v.contentHashCode()
    }
    class LongArray(val v: kotlin.LongArray) : Nbt() {
        override val id = 12
        override fun equals(other: Any?) = other is LongArray && other.v.contentEquals(v)
        override fun hashCode() = v.contentHashCode()
    }

    companion object {
        fun compound(vararg pairs: Pair<String, Nbt>) = Compound(LinkedHashMap(pairs.toMap()))
        fun intList(vararg v: kotlin.Int) = ListTag(3, v.map { Int(it) })
    }
}

object NbtCodec {
    /** Plafond de sécurité pour les fichiers importés (16 Mo). */
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_DEPTH = 512

    fun write(root: Nbt.Compound, rootName: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(10); writeString(out, rootName); writePayload(out, root)
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): Pair<String, Nbt.Compound> {
        if (bytes.size > MAX_BYTES) throw UserFacingException("Fichier NBT trop volumineux.")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val type = b.get().toInt()
            if (type != 10) throw UserFacingException("Ce fichier n'est pas un NBT Bedrock valide (racine de type $type).")
            val name = readString(b)
            return name to (readPayload(b, 10, 0) as Nbt.Compound)
        } catch (e: java.nio.BufferUnderflowException) {
            throw UserFacingException("Fichier NBT tronqué ou endommagé.")
        }
    }

    private fun le(size: kotlin.Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.write(le(2).putShort(bytes.size.toShort()).array()); out.write(bytes)
    }

    private fun writePayload(out: ByteArrayOutputStream, t: Nbt) {
        when (t) {
            is Nbt.Byte -> out.write(t.v.toInt())
            is Nbt.Short -> out.write(le(2).putShort(t.v).array())
            is Nbt.Int -> out.write(le(4).putInt(t.v).array())
            is Nbt.Long -> out.write(le(8).putLong(t.v).array())
            is Nbt.Float -> out.write(le(4).putFloat(t.v).array())
            is Nbt.Double -> out.write(le(8).putDouble(t.v).array())
            is Nbt.ByteArray -> { out.write(le(4).putInt(t.v.size).array()); out.write(t.v) }
            is Nbt.Str -> writeString(out, t.v)
            is Nbt.ListTag -> {
                out.write(if (t.items.isEmpty()) t.elementType else t.items[0].id)
                out.write(le(4).putInt(t.items.size).array())
                t.items.forEach { writePayload(out, it) }
            }
            is Nbt.Compound -> {
                for ((k, v) in t.map) { out.write(v.id); writeString(out, k); writePayload(out, v) }
                out.write(0)
            }
            is Nbt.IntArray -> { out.write(le(4).putInt(t.v.size).array()); t.v.forEach { out.write(le(4).putInt(it).array()) } }
            is Nbt.LongArray -> { out.write(le(4).putInt(t.v.size).array()); t.v.forEach { out.write(le(8).putLong(it).array()) } }
        }
    }

    private fun readString(b: ByteBuffer): String {
        val len = b.getShort().toInt() and 0xFFFF
        val bytes = kotlin.ByteArray(len); b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun count(b: ByteBuffer): kotlin.Int {
        val n = b.getInt()
        if (n < 0 || n > b.remaining()) throw UserFacingException("Fichier NBT endommagé (taille de liste invalide).")
        return n
    }

    private fun readPayload(b: ByteBuffer, type: kotlin.Int, depth: kotlin.Int): Nbt {
        if (depth > MAX_DEPTH) throw UserFacingException("Fichier NBT trop imbriqué.")
        return when (type) {
            1 -> Nbt.Byte(b.get())
            2 -> Nbt.Short(b.getShort())
            3 -> Nbt.Int(b.getInt())
            4 -> Nbt.Long(b.getLong())
            5 -> Nbt.Float(b.getFloat())
            6 -> Nbt.Double(b.getDouble())
            7 -> Nbt.ByteArray(kotlin.ByteArray(count(b)).also { b.get(it) })
            8 -> Nbt.Str(readString(b))
            9 -> {
                val et = b.get().toInt()
                val n = count(b)
                Nbt.ListTag(et, List(n) { readPayload(b, et, depth + 1) })
            }
            10 -> {
                val c = Nbt.Compound()
                while (true) {
                    val t = b.get().toInt()
                    if (t == 0) break
                    val name = readString(b)
                    c[name] = readPayload(b, t, depth + 1)
                }
                c
            }
            11 -> { val n = count(b); Nbt.IntArray(kotlin.IntArray(n) { b.getInt() }) }
            12 -> { val n = count(b); Nbt.LongArray(kotlin.LongArray(n) { b.getLong() }) }
            else -> throw UserFacingException("Fichier NBT endommagé (type de balise inconnu $type).")
        }
    }
}
