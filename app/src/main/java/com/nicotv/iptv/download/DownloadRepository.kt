package com.nicotv.iptv.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.nicotv.iptv.data.database.dao.DownloadDao
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.data.database.entity.EpisodeEntity
import com.nicotv.iptv.domain.model.Movie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Téléchargements locaux (mode avion). Réutilise android.app.DownloadManager +
 * un polling coroutine — même stratégie que UpdateManager pour l'OTA (le broadcast
 * ACTION_DOWNLOAD_COMPLETE s'est révélé peu fiable sur Fire OS), généralisée à
 * plusieurs téléchargements simultanés (un film/une saison entière).
 */
class DownloadRepository(private val context: Context, private val dao: DownloadDao) {

    private val appContext = context.applicationContext
    private val dm get() = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun getAllFlow() = dao.getAll()

    suspend fun localPathFor(key: String): String? = withContext(Dispatchers.IO) {
        val path = dao.getCompleted(key)?.localPath ?: return@withContext null
        path.takeIf { File(it).exists() }
    }

    fun enqueueMovie(movie: Movie) {
        enqueue(
            DownloadEntity(
                key = DownloadEntity.movieKey(movie.id),
                type = DownloadEntity.TYPE_MOVIE,
                title = movie.title,
                posterUrl = movie.posterUrl,
                sourceUrl = movie.streamUrl,
                state = DownloadEntity.STATE_QUEUED
            )
        )
    }

    fun enqueueEpisode(ep: EpisodeEntity, seriesTitle: String, posterUrl: String = "") {
        enqueue(
            DownloadEntity(
                key = ep.fileKey,
                type = DownloadEntity.TYPE_EPISODE,
                seriesId = ep.seriesId,
                title = seriesTitle,
                episodeTitle = ep.episodeTitle,
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.episodeNumber,
                posterUrl = posterUrl,
                sourceUrl = ep.streamUrl,
                state = DownloadEntity.STATE_QUEUED
            )
        )
    }

    /** Enqueue tous les épisodes d'une saison d'un coup ; le polling unique suit tout le lot. */
    fun enqueueSeason(episodes: List<EpisodeEntity>, seriesTitle: String, posterUrl: String = "") {
        episodes.forEach { enqueueEpisode(it, seriesTitle, posterUrl) }
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByKey(key) ?: return@withContext
        // dm.remove() annule le téléchargement encore actif côté OS (sinon il continue
        // en arrière-plan malgré la suppression de la ligne Room — le fichier réapparaît
        // au fur et à mesure qu'il continue d'écrire, jamais nettoyé nulle part, et le
        // téléchargement zombie sature CPU/disque/réseau de l'appareil). Supprime aussi
        // le fichier lui-même pour un téléchargement terminé.
        try {
            if (entry.osDownloadId > 0) dm.remove(entry.osDownloadId)
        } catch (e: Exception) {
            Log.w(TAG, "delete: échec dm.remove: ${e.message}")
        }
        try {
            if (entry.localPath.isNotBlank()) File(entry.localPath).delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete: échec suppression fichier: ${e.message}")
        }
        dao.delete(key)
    }

    private fun enqueue(entry: DownloadEntity) {
        pollScope.launch {
            try {
                dao.upsert(entry)
                val fileName = sanitizeFileName(entry.key) + extensionFromUrl(entry.sourceUrl)
                val request = DownloadManager.Request(Uri.parse(entry.sourceUrl)).apply {
                    setTitle(entry.title)
                    setDescription(entry.episodeTitle.ifBlank { "Téléchargement…" })
                    // VISIBLE_NOTIFY_COMPLETED nécessite que le système poste une notif pour
                    // le compte de l'app — depuis Android 13, ça peut échouer silencieusement
                    // sans la permission POST_NOTIFICATIONS (non déclarée ici, et pas besoin :
                    // l'app a déjà sa propre UI de progression, badges + écran Téléchargements).
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    setDestinationInExternalFilesDir(appContext, DOWNLOAD_SUBDIR, fileName)
                }
                val osId = dm.enqueue(request)
                val localPath = File(appContext.getExternalFilesDir(DOWNLOAD_SUBDIR), fileName).absolutePath
                dao.upsert(entry.copy(state = DownloadEntity.STATE_DOWNLOADING, osDownloadId = osId, localPath = localPath))
                ensurePolling()
            } catch (e: Exception) {
                Log.e(TAG, "enqueue failed for ${entry.key}", e)
                dao.upsert(entry.copy(state = DownloadEntity.STATE_FAILED))
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Échec du téléchargement : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Relance le suivi des téléchargements en cours (process tué pendant un download). */
    fun resumePendingDownloads() {
        pollScope.launch {
            cleanupOrphanFiles()
            ensurePolling()
        }
    }

    /** Supprime les fichiers du dossier downloads/ non référencés par une ligne Room —
     * rattrape les fichiers laissés par un téléchargement annulé avant le fix de
     * dm.remove() (delete() ne coupait pas le job DownloadManager sous-jacent, qui
     * continuait à écrire en arrière-plan indéfiniment). */
    private suspend fun cleanupOrphanFiles() = withContext(Dispatchers.IO) {
        val dir = appContext.getExternalFilesDir(DOWNLOAD_SUBDIR) ?: return@withContext
        val files = dir.listFiles() ?: return@withContext
        if (files.isEmpty()) return@withContext
        val knownPaths = dao.getAllOnce().map { it.localPath }.toSet()
        for (f in files) {
            if (f.absolutePath !in knownPaths) {
                Log.w(TAG, "cleanupOrphanFiles: suppression fichier orphelin ${f.name}")
                f.delete()
            }
        }
    }

    private suspend fun CoroutineScope.ensurePolling() {
        if (!pollingActive.compareAndSet(false, true)) return
        try {
            while (isActive) {
                val active = dao.getActive()
                if (active.isEmpty()) break
                val ids = active.map { it.osDownloadId }.filter { it > 0 }.toLongArray()
                if (ids.isEmpty()) { delay(1500); continue }
                try {
                    dm.query(DownloadManager.Query().setFilterById(*ids))?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                        val statusIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        val reasonIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        val bytesIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        while (cursor.moveToNext()) {
                            val osId = cursor.getLong(idIdx)
                            val row = active.firstOrNull { it.osDownloadId == osId } ?: continue
                            val bytes = cursor.getLong(bytesIdx)
                            val total = cursor.getLong(totalIdx)
                            val status = cursor.getInt(statusIdx)
                            val newState = when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> DownloadEntity.STATE_COMPLETED
                                DownloadManager.STATUS_FAILED -> DownloadEntity.STATE_FAILED
                                else -> DownloadEntity.STATE_DOWNLOADING
                            }
                            if (status == DownloadManager.STATUS_FAILED) {
                                val reason = failureReasonLabel(cursor.getInt(reasonIdx))
                                Log.w(TAG, "download ${row.key} FAILED: $reason")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(appContext, "${row.title} — échec : $reason", Toast.LENGTH_LONG).show()
                                }
                            }
                            dao.updateProgress(row.key, newState, bytes, total, row.localPath)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "polling query failed: ${e.message}")
                }
                delay(1500)
            }
        } finally {
            pollingActive.set(false)
        }
    }

    /** Traduit DownloadManager.COLUMN_REASON (code ERROR_* côté OS) en message
     * compréhensible — remonté à l'utilisateur en Toast quand un téléchargement échoue,
     * pour ne pas se retrouver avec un "Échec" générique impossible à diagnostiquer
     * sans device connecté (cf. le film qui ne se téléchargeait plus, espace disque
     * soupçonné mais jamais confirmé faute de retour précis). */
    private fun failureReasonLabel(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "espace de stockage insuffisant"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "stockage externe indisponible"
        DownloadManager.ERROR_CANNOT_RESUME -> "connexion perdue, reprise impossible"
        DownloadManager.ERROR_FILE_ERROR -> "erreur d'écriture du fichier"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "erreur réseau (données)"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "trop de redirections"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "réponse serveur inattendue"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "le fichier existe déjà"
        else -> "erreur inconnue (code $reason) — lien Debrid peut-être expiré"
    }

    private fun sanitizeFileName(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun extensionFromUrl(url: String): String {
        val path = Uri.parse(url).lastPathSegment ?: return ""
        val dot = path.lastIndexOf('.')
        return if (dot >= 0) path.substring(dot) else ""
    }

    companion object {
        private const val TAG = "DownloadRepository"
        private const val DOWNLOAD_SUBDIR = "downloads"

        // Comme UpdateManager.pollScope : indépendant du cycle de vie d'une Activity,
        // le polling doit survivre à un changement d'écran pendant le téléchargement.
        private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Une seule boucle de polling active à la fois, quel que soit le nombre
        // d'entrées en cours (une requête DownloadManager.Query groupée par tick).
        private val pollingActive = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
