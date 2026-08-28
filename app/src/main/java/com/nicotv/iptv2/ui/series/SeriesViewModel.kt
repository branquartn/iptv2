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
import com.nicotv.iptv2.util.isFrenchLabel
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class SeriesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allSeries = repository.getSeries().asLiveData()

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel/MoviesViewModel, même principe.
    val selectedCategory = MutableLiveData<String?>(null)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
        addSource(allSeries) { list ->
            value = list.map { it.category }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
        }
    }

    val filteredSeries: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        fun filter() {
            var series = allSeries.value ?: return
            selectedCategory.value?.let { cat -> series = series.filter { it.category == cat } }
            val query = searchQuery.value.orEmpty().trim()
            val queryFolded = query.foldAccents()
            value = if (query.isBlank()) series
                    else series.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
        }
        addSource(allSeries) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
    }

    fun toggleFavorite(series: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(series.id, FavoriteEntity.Type.SERIES, series.isFavorite)
        }
    }
}
