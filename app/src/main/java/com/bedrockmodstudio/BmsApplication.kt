package com.bedrockmodstudio

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.packaging.ImportReport
import com.bedrockmodstudio.core.packaging.PackImporter
import com.bedrockmodstudio.core.packaging.ProjectExporter
import com.bedrockmodstudio.core.storage.ErrorLog
import com.bedrockmodstudio.core.storage.ProjectStore
import java.io.File

/** Dépendances partagées de l'application (pas de serveur : tout est local). */
class AppContainer(context: Context) {
    val store = ProjectStore(File(context.filesDir, "projects"))
    val errorLog = ErrorLog(File(context.filesDir, "logs/errors.log"))
    val exporter = ProjectExporter(store)
    val exportDir = File(context.cacheDir, "exports")
    val importer = PackImporter(store, File(context.cacheDir, "imports"))
    private val prefs = context.getSharedPreferences("bms_prefs", Context.MODE_PRIVATE)

    /** Dernier rapport d'import, affiché par l'écran de rapport. */
    @Volatile var lastImportReport: ImportReport? = null
    @Volatile var lastImportProjectId: String? = null

    var defaultVersion: String
        get() = prefs.getString("default_version", null)?.takeIf { BedrockVersions.byId(it) != null } ?: BedrockVersions.DEFAULT.id
        set(v) = prefs.edit { putString("default_version", v) }

    var welcomeSeen: Boolean
        get() = prefs.getBoolean("welcome_seen", false)
        set(v) = prefs.edit { putBoolean("welcome_seen", v) }

    var defaultAuthor: String
        get() = prefs.getString("default_author", "") ?: ""
        set(v) = prefs.edit { putString("default_author", v) }
}

class BmsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { container.errorLog.log("Plantage de l'application (${thread.name})", error) }
            previous?.uncaughtException(thread, error)
        }
        // Les exports temporaires de plus de 2 jours sont nettoyés (les projets ne sont jamais touchés).
        val limit = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
        container.exportDir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }
}

val Context.container: AppContainer get() = (applicationContext as BmsApplication).container
