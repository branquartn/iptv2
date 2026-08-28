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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ⚠️ Filtre en coroutine debouncée — cf. MoviesViewModel.filteredMovies,
    // même raison (foldAccents() sur tout le catalogue à chaque frappe, en
    // synchrone sur le thread principal, saccadait l'app).
    val filteredSeries: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                var series = allSeries.value ?: return@launch
                selectedCategory.value?.let { cat -> series = series.filter { it.category == cat } }
                val query = searchQuery.value.orEmpty().trim()
                if (query.isNotBlank()) {
                    val queryFolded = query.foldAccents()
                    series = withContext(Dispatchers.Default) {
                        series.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
                    }
                }
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
