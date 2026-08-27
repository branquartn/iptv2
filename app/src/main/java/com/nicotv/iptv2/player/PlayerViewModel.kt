package com.nicotv.iptv2.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    // Position mémorisée en RAM pour éviter la race condition DB lors du retour en avant-plan
    private var cachedHistoryKey = ""
    private var cachedPositionMs = 0L

    /** [historyKey] = "m<id>" pour un film, "e:<fileKey>" pour un épisode.
     * [progressId] = id utilisé pour retrouver l'élément dans "Reprendre la lecture"
     * (movie.id pour un film, episode.watchKey pour un épisode). */
    fun savePosition(historyKey: String, progressId: Long, title: String, positionMs: Long, durationMs: Long, forceFinished: Boolean = false) {
        cachedHistoryKey = historyKey
        cachedPositionMs = if (forceFinished) 0L else positionMs
        // appScope (pas viewModelScope) : cette écriture est déclenchée par
        // saveAndRelease() juste avant finish() (retour, fin de film, enchaînement
        // auto d'épisode). viewModelScope serait annulé par la destruction du
        // ViewModel avant la fin de l'écriture → épisode/film non marqué correctement.
        app.appScope.launch {
            repository.saveWatchPosition(historyKey, progressId, title, positionMs, durationMs, forceFinished)
        }
    }

    suspend fun getResumePosition(historyKey: String): Long {
        if (cachedHistoryKey == historyKey && cachedPositionMs > 0) return cachedPositionMs
        return repository.getWatchPosition(historyKey)
    }

    /** Retourne l'épisode suivant dans la série, ou null si c'est le dernier. */
    suspend fun getNextEpisode(currentWatchKey: Long, seriesId: Long): EpisodeEntity? =
        repository.getNextEpisode(currentWatchKey, seriesId)
}
