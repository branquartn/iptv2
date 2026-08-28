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
import com.nicotv.iptv2.util.isFrenchLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // ⚠️ Recherche par titre en SQL — cf. MoviesViewModel.filteredMovies, même
    // raison et même principe (repository.searchSeriesByTitle).
    val filteredSeries: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                val query = searchQuery.value.orEmpty().trim()
                var series = if (query.isBlank()) allSeries.value ?: emptyList()
                             else repository.searchSeriesByTitle(query)
                selectedCategory.value?.let { cat -> series = series.filter { it.category == cat } }
                value = series
            }
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
