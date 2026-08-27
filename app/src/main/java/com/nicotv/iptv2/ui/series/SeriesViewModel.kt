package com.nicotv.iptv2.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.util.foldAccents
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class SeriesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allSeries = repository.getSeries().asLiveData()

    val searchQuery = MutableLiveData("")

    val filteredSeries: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        fun filter() {
            val series = allSeries.value ?: return
            val query = searchQuery.value.orEmpty().trim()
            val queryFolded = query.foldAccents()
            value = if (query.isBlank()) series
                    else series.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
        }
        addSource(allSeries) { filter() }
        addSource(searchQuery) { filter() }
    }

    fun toggleFavorite(series: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(series.id, FavoriteEntity.Type.SERIES, series.isFavorite)
        }
    }
}
