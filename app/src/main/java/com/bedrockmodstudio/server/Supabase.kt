package com.bedrockmodstudio.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Erreur expliquée simplement (réseau, identifiants, droits…). */
class CloudException(message: String) : Exception(message)

/**
 * Petit coffre chiffré (clé matérielle du Keystore Android) pour la session Supabase
 * et les clés d'agent des serveurs. Rien n'est écrit en clair.
 */
class SecretStore(context: Context, name: String) {
    private val file = File(context.filesDir, "cloud/$name.bin")
    private val alias = "bms_cloud_$name"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    fun read(): JsonObject = runCatching {
        val b = file.readBytes()
        val ivLen = b[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen))) }
        JsonParser.parse(String(c.doFinal(b.copyOfRange(1 + ivLen, b.size)), Charsets.UTF_8)).valueOrNull() as? JsonObject
    }.getOrNull() ?: JsonObject()

    fun write(o: JsonObject) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = c.doFinal(JsonWriter.write(o).toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + data)
    }

    fun clear() { file.delete() }
}

data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String)

/**
 * Client Supabase minimal (HTTP + JSON, sans bibliothèque) : comptes (GoTrue), tables (PostgREST)
 * et stockage. Toutes les fonctions sont bloquantes : à appeler depuis Dispatchers.IO.
 *
 * Le projet Supabase appartient à l'utilisateur (offre gratuite) ; l'application n'utilise que la clé
 * publique « anon », la sécurité étant assurée par les règles RLS de la migration SQL.
 */
class SupabaseClient(context: Context) {
    private val prefs = context.getSharedPreferences("bms_cloud", Context.MODE_PRIVATE)
    private val sessionStore = SecretStore(context, "session")
    val agentKeys = SecretStore(context, "agent_keys")

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit { putString("url", v.trim().trimEnd('/')) }
    var anonKey: String
        get() = prefs.getString("anon", "") ?: ""
        set(v) = prefs.edit { putString("anon", v.trim()) }

    val configured: Boolean get() = url.startsWith("https://") && anonKey.length > 20

    @Volatile private var cached: Session? = null

    val session: Session?
        get() = cached ?: sessionStore.read().let { o ->
            val at = o.str("access_token") ?: return null
            Session(at, o.str("refresh_token") ?: "", o["expires_at"]?.asDouble?.toLong() ?: 0, o.str("user_id") ?: "", o.str("email") ?: "")
                .also { cached = it }
        }

    private fun saveSession(o: JsonObject) {
        val user = o.obj("user")
        val s = Session(
            accessToken = o.str("access_token") ?: throw CloudException("Réponse de connexion inattendue."),
            refreshToken = o.str("refresh_token") ?: "",
            expiresAt = System.currentTimeMillis() / 1000 + (o["expires_in"]?.asInt ?: 3600),
            userId = user?.str("id") ?: session?.userId ?: "",
            email = user?.str("email") ?: session?.email ?: "",
        )
        sessionStore.write(obj("access_token" to s.accessToken, "refresh_token" to s.refreshToken, "expires_at" to s.expiresAt, "user_id" to s.userId, "email" to s.email))
        cached = s
    }

    fun signOut() { sessionStore.clear(); cached = null }

    // ---------------------------------------------------------------- HTTP

    class Response(val code: Int, val body: String) {
        val json: JsonValue? get() = if (body.isBlank()) null else JsonParser.parse(body).valueOrNull()
    }

    private fun http(method: String, path: String, body: ByteArray? = null, contentType: String = "application/json",
                     headers: Map<String, String> = emptyMap(), auth: Boolean = true): Response {
        if (!configured) throw CloudException("Supabase n'est pas configuré.")
        val c = (URL(url + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("apikey", anonKey)
            val token = if (auth) freshSession()?.accessToken ?: anonKey else anonKey
            setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", contentType) }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b) } }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Response(code, text)
        } catch (e: java.io.IOException) {
            throw CloudException("Connexion impossible à Supabase (${e.javaClass.simpleName}). Vérifie Internet et l'adresse du projet.")
        } finally { c.disconnect() }
    }

    private fun check(r: Response, what: String): Response {
        if (r.code in 200..299) return r
        val j = r.json as? JsonObject
        val msg = j?.str("msg") ?: j?.str("message") ?: j?.str("error_description") ?: j?.str("error") ?: r.body.take(200)
        throw CloudException(when {
            r.code == 401 || r.code == 403 -> "$what : accès refusé ($msg). Reconnecte-toi."
            r.code == 404 && msg.contains("bms_", true) -> "$what : tables introuvables. As-tu exécuté le script SQL dans Supabase ?"
            else -> "$what : $msg (code ${r.code})"
        })
    }

    // ---------------------------------------------------------------- Comptes

    private fun freshSession(): Session? {
        val s = session ?: return null
        if (s.expiresAt - System.currentTimeMillis() / 1000 > 60 || s.refreshToken.isEmpty()) return s
        val r = http("POST", "/auth/v1/token?grant_type=refresh_token", JsonWriter.write(obj("refresh_token" to s.refreshToken)).toByteArray(), auth = false)
        if (r.code !in 200..299) { signOut(); throw CloudException("Session expirée : reconnecte-toi.") }
        saveSession(r.json as JsonObject)
        return cached
    }

    fun signIn(email: String, password: String) {
        val r = check(http("POST", "/auth/v1/token?grant_type=password", JsonWriter.write(obj("email" to email.trim(), "password" to password)).toByteArray(), auth = false), "Connexion")
        saveSession(r.json as? JsonObject ?: throw CloudException("Réponse inattendue."))
    }

    /** Renvoie vrai si le compte est utilisable tout de suite, faux s'il faut confirmer l'adresse e-mail. */
    fun signUp(email: String, password: String): Boolean {
        if (password.length < 8) throw CloudException("Mot de passe trop court (8 caractères minimum).")
        val r = check(http("POST", "/auth/v1/signup", JsonWriter.write(obj("email" to email.trim(), "password" to password)).toByteArray(), auth = false), "Création du compte")
        val o = r.json as? JsonObject ?: return false
        if (o.str("access_token") != null) { saveSession(o); return true }
        return false
    }

    // ---------------------------------------------------------------- Tables

    fun select(table: String, query: String): List<JsonObject> =
        (check(http("GET", "/rest/v1/$table?$query"), "Lecture").json as? JsonArray)?.items?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun insert(table: String, row: JsonObject): JsonObject =
        (check(http("POST", "/rest/v1/$table", JsonWriter.write(row).toByteArray(), headers = mapOf("Prefer" to "return=representation")), "Enregistrement")
            .json as? JsonArray)?.items?.firstOrNull() as? JsonObject ?: JsonObject()

    /** Appel d'une fonction SQL (les modifications passent par des fonctions : HttpURLConnection ne gère pas PATCH). */
    fun rpc(fn: String, args: JsonObject): JsonValue? = check(http("POST", "/rest/v1/rpc/$fn", JsonWriter.write(args).toByteArray()), "Modification").json

    fun delete(table: String, filter: String) { check(http("DELETE", "/rest/v1/$table?$filter"), "Suppression") }

    // ---------------------------------------------------------------- Stockage

    fun upload(bucket: String, path: String, bytes: ByteArray) {
        check(http("POST", "/storage/v1/object/$bucket/${encPath(path)}", bytes, "application/octet-stream", mapOf("x-upsert" to "true")), "Envoi du fichier")
    }

    /** Lien de téléchargement signé (lecture seule, ce fichier uniquement). */
    fun signedUrl(bucket: String, path: String, seconds: Long): String {
        val r = check(http("POST", "/storage/v1/object/sign/$bucket/${encPath(path)}", JsonWriter.write(obj("expiresIn" to seconds)).toByteArray()), "Lien de téléchargement")
        val rel = (r.json as? JsonObject)?.str("signedURL") ?: (r.json as? JsonObject)?.str("signedUrl") ?: throw CloudException("Lien signé absent.")
        return url + "/storage/v1" + (if (rel.startsWith("/")) rel else "/$rel")
    }

    fun removeObjects(bucket: String, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching { http("DELETE", "/storage/v1/object/$bucket", JsonWriter.write(obj("prefixes" to JsonArray(paths.map { JsonString(it) }))).toByteArray()) }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun encPath(p: String) = p.split('/').joinToString("/") { enc(it).replace("+", "%20") }

        fun newAgentKey(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
