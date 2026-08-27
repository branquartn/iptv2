package com.nicotv.iptv.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.domain.model.Movie
import com.nicotv.iptv.util.foldAccents

class SeriesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.mediaRepository
    private val username = app.sessionManager.getUsername()

    private val allSeries = repository.getSeries(username).asLiveData()
    private val isOnline = app.isOnline
    private val downloads = app.downloadRepository.getAllFlow().asLiveData()

    val searchQuery = MutableLiveData("")

    // Rubrique « Nouveautés » (badge accueil, comme new-series côté PWA).
    val newOnly = MutableLiveData(false)

    val filteredSeries: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        fun filter() {
            val series = allSeries.value ?: return
            val query = searchQuery.value.orEmpty().trim()
            var base = if (newOnly.value == true) series.filter { it.isNew } else series
            // Hors-ligne (mode avion) : ne garder que les séries ayant au moins un
            // épisode téléchargé (saison partielle acceptée, pas besoin du tout).
            if (isOnline.value == false) {
                val seriesWithDownload = downloads.value.orEmpty()
                    .filter { it.type == DownloadEntity.TYPE_EPISODE && it.state == DownloadEntity.STATE_COMPLETED }
                    .map { it.seriesId }.toSet()
                base = base.filter { it.id in seriesWithDownload }
            }
            val queryFolded = query.foldAccents()
            value = if (query.isBlank()) base
                    else base.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
        }
        addSource(allSeries) { filter() }
        addSource(searchQuery) { filter() }
        addSource(newOnly) { filter() }
        addSource(isOnline) { filter() }
        addSource(downloads) { filter() }
    }
}
