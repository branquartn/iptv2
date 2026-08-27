package com.nicotv.iptv2.update

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.AppConfig
import com.nicotv.iptv2.BuildConfig
import com.nicotv.iptv2.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

// Throttle partagé entre tous les écrans qui appellent checkForAppUpdate() (login,
// accueil) : évite un double appel réseau si l'utilisateur passe de l'un à l'autre
// en moins de 2 minutes.
private var lastUpdateCheck = 0L
private const val UPDATE_CHECK_INTERVAL_MS = 2 * 60 * 1000L

/**
 * Vérifie une mise à jour OTA et propose l'installation si disponible. Ne nécessite
 * pas d'être connecté (version.json est public) : peut donc être appelé aussi bien
 * depuis LoginActivity que MainActivity, pour ne pas dépendre du login pour se
 * rattraper d'un bug qui bloquerait justement la connexion.
 */
fun FragmentActivity.checkForAppUpdate() {
    val updateManager = UpdateManager(this)
    // Toujours vérifié, même si le check réseau est throttlé : reprend une installation
    // dont le téléchargement s'est terminé pendant que l'app était en arrière-plan.
    updateManager.installPendingDownloadIfReady()

    val now = System.currentTimeMillis()
    if (now - lastUpdateCheck <= UPDATE_CHECK_INTERVAL_MS) return
    lastUpdateCheck = now

    lifecycleScope.launch {
        val remote = updateManager.checkForUpdate() ?: return@launch
        if (isFinishing || isDestroyed) return@launch
        val changelog = remote.changelog.ifBlank { "Une nouvelle version est disponible." }
        val dialog = AlertDialog.Builder(this@checkForAppUpdate)
            .setTitle("Mise à jour disponible — v${remote.versionName}")
            .setMessage(changelog)
            .setCancelable(false)
            .setPositiveButton("Mettre à jour") { _, _ -> updateManager.downloadAndInstall(remote) }
            .setNegativeButton("Plus tard", null)
            .create()
        dialog.show()
        // Fond arrondi comme les autres dialogs de l'app (cf. showQuitDialog / fiche
        // acteur) — sinon rectangle système gris incohérent.
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }
}

/** Infos d'une version distante lues depuis version.json. */
data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String
)

/**
 * Vérifie la disponibilité d'une mise à jour, télécharge l'APK et lance son installation.
 * Côté serveur : héberger un version.json + l'APK (voir AppConfig.Update.VERSION_URL).
 */
class UpdateManager(private val context: Context) {

    private val client = OkHttpClient()

    /** Renvoie la version distante si elle est plus récente que l'APK installé, sinon null. */
    suspend fun checkForUpdate(): RemoteVersion? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(AppConfig.Update.VERSION_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val remote = RemoteVersion(
                    versionCode = json.optInt("versionCode", 0),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.optString("apkUrl", ""),
                    changelog = json.optString("changelog", "")
                )
                if (remote.versionCode > BuildConfig.VERSION_CODE && remote.apkUrl.isNotBlank()) {
                    remote
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkForUpdate failed: ${e.message}")
            null
        }
    }

    /** Télécharge l'APK puis déclenche l'installation à la fin du téléchargement. */
    fun downloadAndInstall(remote: RemoteVersion) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "nicotv-${remote.versionName.ifBlank { remote.versionCode.toString() }}.apk"

            // Supprime un éventuel ancien téléchargement
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName).delete()

            val request = DownloadManager.Request(Uri.parse(remote.apkUrl)).apply {
                setTitle("NicoTV ${remote.versionName}")
                setDescription("Téléchargement de la mise à jour…")
                // Notif système masquée, comme pour les téléchargements mode avion
                // (DOWNLOAD_WITHOUT_NOTIFICATION déclarée) — un Toast suffit déjà ici.
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadId = dm.enqueue(request)
            Toast.makeText(context, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show()

            // Persisté pour installPendingDownloadIfReady() : filet si le process est
            // tué en arrière-plan pendant le téléchargement (le polling ci-dessous
            // s'arrête avec le process) — au prochain démarrage de l'app, on retrouve
            // le download terminé et on installe.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putLong(KEY_DOWNLOAD_ID, downloadId)
                putString(KEY_FILE_NAME, fileName)
            }

            // Polling plutôt que BroadcastReceiver sur ACTION_DOWNLOAD_COMPLETE : ce
            // broadcast s'est révélé peu fiable (raté sur certains appareils/Fire OS),
            // ce qui obligeait l'utilisateur à changer d'écran et revenir pour que le
            // filet onStart (installPendingDownloadIfReady) rattrape l'install. Le
            // polling interroge directement DownloadManager, indépendamment de tout
            // broadcast, et utilise applicationContext pour survivre à un changement
            // d'écran/activité pendant le téléchargement.
            pollDownload(downloadId, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall failed", e)
            Toast.makeText(context, "Erreur de mise à jour : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pollDownload(downloadId: Long, fileName: String) {
        val appContext = context.applicationContext
        pollScope.launch {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isActive) {
                val status = try {
                    dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        } else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pollDownload query failed: ${e.message}")
                    null
                }

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        clearPendingDownload()
                        val apk = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                        withContext(Dispatchers.Main) {
                            installApkOnce(downloadId, apk)
                        }
                        return@launch
                    }
                    null, DownloadManager.STATUS_FAILED -> {
                        clearPendingDownload()
                        return@launch
                    }
                    else -> delay(1000)
                }
            }
        }
    }

    /**
     * Filet de rattrapage appelé à chaque ouverture de l'app (checkForAppUpdate) :
     * si un téléchargement lancé précédemment est terminé mais n'a pas déclenché
     * l'installation (récepteur mort avec le process en arrière-plan), on la relance
     * nous-mêmes ici. Ne fait rien si aucun téléchargement n'est en attente.
     */
    fun installPendingDownloadIfReady() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val fileName = prefs.getString(KEY_FILE_NAME, null)
        if (downloadId == -1L || fileName == null) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val status = try {
            dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "installPendingDownloadIfReady query failed: ${e.message}")
            null
        }

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                clearPendingDownload()
                val apk = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                installApkOnce(downloadId, apk)
            }
            null, DownloadManager.STATUS_FAILED -> clearPendingDownload()
            else -> Unit // toujours en cours de téléchargement, on revérifiera au prochain démarrage
        }
    }

    private fun clearPendingDownload() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    /**
     * Lance l'installation une seule fois par téléchargement. Le polling et le filet
     * onStart (installPendingDownloadIfReady) peuvent tous deux détecter STATUS_SUCCESSFUL
     * dans la brève fenêtre avant clearPendingDownload → sans ce garde, deux prompts
     * d'installation s'ouvriraient.
     */
    private fun installApkOnce(downloadId: Long, apk: File) {
        if (!installedDownloadIds.add(downloadId)) return
        if (apk.exists()) installApk(apk) else
            Toast.makeText(context, "Échec du téléchargement", Toast.LENGTH_SHORT).show()
    }

    private fun installApk(apk: File) {
        // Pas de vérification préventive de canInstallPackages() : sur Android TV/Shield,
        // le deep-link ACTION_MANAGE_UNKNOWN_APP_SOURCES ne résout pas toujours vers un
        // écran utilisable, ce qui bloquait l'installation sans recours. On tente
        // directement l'intent d'installation — si la permission manque, le système
        // affiche lui-même l'écran « autoriser cette source » au sein du flux d'install
        // (fonctionne sur toutes les plateformes, y compris TV). Le fallback ci-dessous
        // ne sert que si aucun installeur ne peut être lancé du tout.
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            if (!canInstallPackages()) {
                requestInstallPermission()
            } else {
                Toast.makeText(context, "Échec de l'installation : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission() {
        // Réinitialise le throttle : quand l'utilisateur revient des paramètres et que
        // onStart se déclenche, le check repart depuis zéro et la dialog de mise à jour
        // réapparaît automatiquement si la permission a été accordée.
        lastUpdateCheck = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val permDialog = AlertDialog.Builder(context)
                    .setTitle("Permission requise")
                    .setMessage(
                        "NicoTV doit être autorisé à installer des applications.\n\n" +
                        "Active « Sources inconnues » pour NicoTV dans les paramètres, " +
                        "puis reviens : la mise à jour démarrera automatiquement."
                    )
                    .setPositiveButton("Ouvrir les paramètres") { _, _ ->
                        context.startActivity(intent)
                    }
                    .setNegativeButton("Annuler", null)
                    .create()
                permDialog.show()
                permDialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
            } catch (e: Exception) {
                Log.e(TAG, "requestInstallPermission failed", e)
                Toast.makeText(
                    context,
                    "Autorise l'installation de sources inconnues pour NicoTV, puis relance la mise à jour.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val PREFS_NAME = "iptv_update"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
        private const val KEY_FILE_NAME = "pending_file_name"

        // Indépendant du cycle de vie d'une Activity : une nouvelle UpdateManager est
        // créée à chaque écran (checkForAppUpdate), mais le polling doit continuer même
        // si l'utilisateur change d'écran pendant le téléchargement.
        private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // downloadId déjà passés à l'installation, pour ne jamais ouvrir deux prompts
        // pour le même téléchargement (polling + filet onStart). Statique : partagé
        // entre toutes les instances d'UpdateManager (une par écran).
        private val installedDownloadIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    }
}
