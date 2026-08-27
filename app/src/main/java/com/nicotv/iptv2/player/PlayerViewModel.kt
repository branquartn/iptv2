package com.nicotv.iptv2.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.mediaRepository

    // Position mémorisée en RAM pour éviter la race condition DB lors du retour en avant-plan
    private var cachedMovieId = -1L
    private var cachedPositionMs = 0L

    // Toujours relayé au repository, quelle que soit la position : c'est lui qui décide
    // (par type film/épisode) d'enregistrer une reprise ou d'effacer l'ancienne en place
    // (cf. saveWatchPosition / MOVIE_MIN_RESUME_MS vs MIN_RESUME_MS).
    fun savePosition(movieId: Long, title: String, positionMs: Long, durationMs: Long, forceFinished: Boolean = false) {
        cachedMovieId = movieId
        cachedPositionMs = if (forceFinished) 0L else positionMs
        // appScope (pas viewModelScope) : cette écriture est déclenchée par
        // saveAndRelease() juste avant finish() (retour, fin de film, enchaînement
        // auto d'épisode). viewModelScope serait annulé par la destruction du
        // ViewModel avant la fin de l'écriture → épisode/film non marqué « vu ».
        app.appScope.launch {
            repository.saveWatchPosition(
                movieId,
                title,
                positionMs,
                durationMs,
                app.sessionManager.getUsername(),
                app.sessionManager.bearer(),
                forceFinished
            )
        }
    }

    suspend fun getResumePosition(movieId: Long): Long {
        if (cachedMovieId == movieId && cachedPositionMs > 0) return cachedPositionMs
        return repository.getWatchPosition(movieId)
    }

    /** Retourne l'épisode suivant dans la série, ou null si c'est le dernier. */
    suspend fun getNextEpisode(currentWatchKey: Long, seriesId: Long): EpisodeEntity? {
        val episodes = repository.getEpisodesForSeries(seriesId)
        val idx = episodes.indexOfFirst { it.watchKey == currentWatchKey }
        return if (idx >= 0 && idx + 1 < episodes.size) episodes[idx + 1] else null
    }

    // Présence temps réel (admin « qui regarde quoi »), cf. commentaire équivalent
    // côté PWA (app.js/sendHeartbeat) : 'stream' n'est requêté qu'au démarrage en
    // lecture directe (film → item.url stocké, ne passe même pas par 'stream'), donc
    // un heartbeat explicite est nécessaire pour une présence qui dure.
    fun sendHeartbeat(movieId: Long, positionMs: Long, durationMs: Long, playing: Boolean) {
        if (movieId == -1L) return
        app.appScope.launch {
            repository.sendHeartbeat(movieId, positionMs, durationMs, playing, app.sessionManager.bearer())
        }
    }

    /** Fermeture propre du lecteur : retire tout de suite la présence (sinon
     * l'admin la verrait « en cours » jusqu'au TTL serveur). appScope comme
     * savePosition() : doit survivre à la destruction de l'activité. */
    fun sendPresenceStop() {
        app.appScope.launch {
            val bearer = app.sessionManager.bearer()
            repository.sendPresenceStop(bearer)
            // Course avec onResume() de l'activité qui reprend la main (retour) : sur
            // Android, l'activité précédente reprend AVANT que celle-ci ne s'arrête,
            // donc son propre reportScreen()/heartbeat "en ligne" peut arriver avant ce
            // presence_stop — qui supprime alors la ligne fraîchement recréée (rien
            // affiché côté admin.nicotv.ovh). Ce heartbeat, séquentiel juste après,
            // garantit le dernier mot quel que soit l'ordre réel des deux appels.
            repository.sendAppHeartbeat(com.nicotv.iptv2.util.PresenceScreen.label, bearer)
        }
    }
}
