package com.bedrockmodstudio.server

import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.packaging.ExportKind
import com.bedrockmodstudio.core.packaging.ExportOutcome
import com.bedrockmodstudio.core.packaging.ProjectExporter
import com.bedrockmodstudio.core.storage.ProjectStore
import java.io.File
import java.time.Instant

/** Serveur tel que stocké dans Supabase (table bms_servers). */
data class CloudServer(
    val id: String, val name: String, val gamemode: String, val difficulty: String, val maxPlayers: Int,
    val levelSeed: String, val viewDistance: Int, val tickDistance: Int, val allowCheats: Boolean, val betaApis: Boolean,
    val port: Int, val status: String, val message: String, val address: String, val version: String,
    val players: List<String>, val metrics: JsonObject, val backups: Int, val lastSeen: Long?,
) {
    /** L'agent a donné signe de vie il y a moins de 30 secondes. */
    val agentOnline: Boolean get() = lastSeen != null && System.currentTimeMillis() - lastSeen < 30_000

    val statusFr: String get() = when {
        status == "not_installed" -> "À installer"
        !agentOnline -> "Machine hors ligne"
        else -> when (status) {
            "online" -> "En ligne"; "offline" -> "Arrêté"; "starting" -> "Démarrage…"; "stopping" -> "Arrêt…"
            "installing" -> "Installation…"; "crashed" -> "Planté"; "error" -> "Erreur"; else -> status
        }
    }

    companion object {
        fun parse(o: JsonObject) = CloudServer(
            id = o.str("id") ?: "", name = o.str("name") ?: "", gamemode = o.str("gamemode") ?: "survival",
            difficulty = o.str("difficulty") ?: "normal", maxPlayers = o["max_players"]?.asInt ?: 5,
            levelSeed = o.str("level_seed") ?: "", viewDistance = o["view_distance"]?.asInt ?: 12, tickDistance = o["tick_distance"]?.asInt ?: 4,
            allowCheats = o["allow_cheats"]?.asBoolean ?: false, betaApis = o["beta_apis"]?.asBoolean ?: false,
            port = o["server_port"]?.asInt ?: 19132, status = o.str("status") ?: "not_installed", message = o.str("status_message") ?: "",
            address = o.str("public_address") ?: "", version = o.str("bds_version") ?: "",
            players = (o["players"] as? JsonArray)?.items?.mapNotNull { (it as? JsonObject)?.str("name") } ?: emptyList(),
            metrics = o.obj("metrics") ?: JsonObject(), backups = (o["backups"] as? JsonArray)?.items?.size ?: 0,
            lastSeen = o.str("last_seen")?.let { runCatching { Instant.parse(normalizeTs(it)).toEpochMilli() }.getOrNull() },
        )

        /** PostgREST renvoie « 2026-11-15T12:00:00.123456+00:00 » : Instant.parse veut « Z » ou un décalage complet. */
        fun normalizeTs(s: String): String = s.replace(" ", "T").let { if (it.endsWith("+00:00")) it.removeSuffix("+00:00") + "Z" else it }

        val GAMEMODES = listOf("survival" to "Survie", "creative" to "Créatif", "adventure" to "Aventure")
        val DIFFICULTIES = listOf("peaceful" to "Paisible", "easy" to "Facile", "normal" to "Normal", "hard" to "Difficile")
    }
}

data class CloudPack(val id: String, val name: String, val kind: String, val path: String, val size: Long, val position: Int, val enabled: Boolean) {
    companion object {
        fun parse(o: JsonObject) = CloudPack(
            o.str("id") ?: "", o.str("name") ?: "", o.str("kind") ?: "behavior", o.str("storage_path") ?: "",
            o["size_bytes"]?.asDouble?.toLong() ?: 0, o["position"]?.asInt ?: 0, o["enabled"]?.asBoolean ?: true,
        )
    }
}

/** Opérations de haut niveau utilisées par les écrans « Serveurs ». */
class ServerRepository(private val cloud: SupabaseClient) {
    companion object {
        const val BUCKET = "bms-packs"
        /** Lien signé valable 10 ans : seul l'agent le connaît, il ne donne accès qu'à ce fichier. */
        const val LINK_SECONDS = 10L * 365 * 24 * 3600
        const val INSTALL_URL = "https://raw.githubusercontent.com/julienots/MOD-MINECRAFT-APP/main/server/agent/install.sh"
    }

    fun servers(): List<CloudServer> = cloud.select("bms_servers", "select=*&order=created_at.asc").map(CloudServer::parse)

    fun server(id: String): CloudServer? = cloud.select("bms_servers", "select=*&id=eq.${SupabaseClient.enc(id)}").firstOrNull()?.let(CloudServer::parse)

    fun create(name: String, gamemode: String, difficulty: String, maxPlayers: Int, seed: String, betaApis: Boolean): CloudServer {
        val key = SupabaseClient.newAgentKey()
        val row = cloud.insert("bms_servers", obj(
            "name" to name.trim().take(48), "gamemode" to gamemode, "difficulty" to difficulty,
            "max_players" to maxPlayers.coerceIn(1, 5), "level_seed" to seed.trim().take(32), "beta_apis" to betaApis,
            "agent_key_hash" to SupabaseClient.sha256(key),
        ))
        val s = CloudServer.parse(row)
        rememberKey(s.id, key)
        return s
    }

    fun rememberKey(serverId: String, key: String) {
        val all = cloud.agentKeys.read(); all[serverId] = com.bedrockmodstudio.core.json.JsonString(key); cloud.agentKeys.write(all)
    }

    fun agentKey(serverId: String): String? = cloud.agentKeys.read().str(serverId)

    /** Nouvelle clé : l'ancienne machine ne pourra plus piloter ce serveur (à réinstaller avec la nouvelle commande). */
    fun regenerateKey(serverId: String): String {
        val key = SupabaseClient.newAgentKey()
        cloud.rpc("bms_update_server", obj("p_server" to serverId, "p_settings" to obj("agent_key_hash" to SupabaseClient.sha256(key))))
        rememberKey(serverId, key)
        return key
    }

    fun installCommand(serverId: String, key: String): String =
        "curl -fsSL $INSTALL_URL | sudo bash -s -- --url ${cloud.url} --anon ${cloud.anonKey} --server $serverId --key $key"

    fun updateSettings(serverId: String, settings: JsonObject) = cloud.rpc("bms_update_server", obj("p_server" to serverId, "p_settings" to settings))

    fun command(serverId: String, kind: String, payload: String = "") {
        cloud.insert("bms_server_commands", obj("server_id" to serverId, "kind" to kind, "payload" to payload.take(500)))
    }

    fun logs(serverId: String, limit: Int = 150): List<String> =
        cloud.select("bms_server_logs", "select=line&server_id=eq.${SupabaseClient.enc(serverId)}&order=id.desc&limit=$limit")
            .mapNotNull { it.str("line") }.reversed()

    /** Dernières commandes et leur résultat (pour afficher « Démarrage demandé », erreurs…). */
    fun recentCommands(serverId: String): List<JsonObject> =
        cloud.select("bms_server_commands", "select=kind,state,result,created_at&server_id=eq.${SupabaseClient.enc(serverId)}&order=id.desc&limit=5")

    fun packs(serverId: String): List<CloudPack> =
        cloud.select("bms_server_packs", "select=*&server_id=eq.${SupabaseClient.enc(serverId)}&order=position.asc,created_at.asc").map(CloudPack::parse)

    /**
     * Exporte un projet de l'application (behavior + resource pack) et l'envoie sur le serveur.
     * L'export passe par la vérification habituelle : un projet avec des erreurs bloquantes est refusé.
     */
    fun addProject(serverId: String, store: ProjectStore, projectId: String, workDir: File): List<String> {
        val meta = store.load(projectId)
        val uid = cloud.session?.userId ?: throw CloudException("Connecte-toi d'abord.")
        val exporter = ProjectExporter(store)
        val existing = packs(serverId)
        var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val added = mutableListOf<String>()
        workDir.mkdirs()
        val files = store.files(projectId)
        for ((kind, exportKind, dir) in listOf(
            Triple("behavior", ExportKind.MCPACK_BEHAVIOR, "behavior_pack"),
            Triple("resource", ExportKind.MCPACK_RESOURCE, "resource_pack"),
        )) {
            if (!files.exists("$dir/manifest.json")) continue
            val out = when (val r = exporter.export(projectId, exportKind, workDir, bumpVersion = false)) {
                is ExportOutcome.Success -> r.file
                is ExportOutcome.Blocked -> throw CloudException("« ${meta.name} » a ${r.report.errors.size} erreur(s) bloquante(s) : ouvre « Vérifier » dans le projet.")
                is ExportOutcome.Failed -> throw CloudException(r.message)
            }
            val bytes = out.readBytes()
            out.delete()
            val name = "${meta.name} (${if (kind == "behavior") "comportements" else "apparence"})"
            val path = "$uid/$serverId/${projectId}_$kind.mcpack"
            cloud.upload(BUCKET, path, bytes)
            val link = cloud.signedUrl(BUCKET, path, LINK_SECONDS)
            val same = existing.firstOrNull { it.path == path }
            if (same != null) {
                // Nouvelle version du même projet : on remplace le fichier, la taille change → l'agent le réinstalle.
                cloud.delete("bms_server_packs", "id=eq.${same.id}")
                cloud.insert("bms_server_packs", obj("server_id" to serverId, "name" to name, "kind" to kind, "storage_path" to path,
                    "download_url" to link, "size_bytes" to bytes.size, "position" to same.position, "enabled" to same.enabled))
            } else {
                cloud.insert("bms_server_packs", obj("server_id" to serverId, "name" to name, "kind" to kind, "storage_path" to path,
                    "download_url" to link, "size_bytes" to bytes.size, "position" to position++))
            }
            added += name
        }
        if (added.isEmpty()) throw CloudException("Ce projet ne contient aucun pack à envoyer.")
        return added
    }

    fun setPack(pack: CloudPack, position: Int? = null, enabled: Boolean? = null) =
        cloud.rpc("bms_update_pack", JsonObject(mapOf(
            // Valeur absente = « ne pas changer » (null SQL) : la clé doit quand même être envoyée.
            "p_pack" to com.bedrockmodstudio.core.json.JsonString(pack.id),
            "p_position" to (position?.let { com.bedrockmodstudio.core.json.JsonNumber(it) } ?: com.bedrockmodstudio.core.json.JsonNull),
            "p_enabled" to (enabled?.let { com.bedrockmodstudio.core.json.JsonBool(it) } ?: com.bedrockmodstudio.core.json.JsonNull),
        )))

    fun removePack(pack: CloudPack) {
        cloud.delete("bms_server_packs", "id=eq.${pack.id}")
        cloud.removeObjects(BUCKET, listOf(pack.path))
    }

    fun deleteServer(serverId: String) {
        val paths = runCatching { packs(serverId).map { it.path } }.getOrDefault(emptyList())
        cloud.delete("bms_servers", "id=eq.${SupabaseClient.enc(serverId)}")
        cloud.removeObjects(BUCKET, paths)
        val all = cloud.agentKeys.read(); all.remove(serverId); cloud.agentKeys.write(all)
    }
}
