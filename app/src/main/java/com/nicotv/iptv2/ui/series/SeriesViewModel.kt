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
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import com.nicotv.iptv2.util.isFrenchLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class SeriesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allSeries = repository.getSeries().asLiveData()

    // Réglage persistant (Réglages > Langue du contenu) — cf. MoviesViewModel,
    // même principe (filtre par code, pas l'heuristique isFrenchLabel).
    // ⚠️ Pas un "exact match" (corrigé 28/08/2026, régression signalée par
    // l'utilisateur : plus aucune série visible) — cf. MoviesViewModel.
    // applyLanguageFilter pour le détail : garde si aucun préfixe détecté OU
    // préfixe == contentLanguage, exclut seulement un préfixe explicite d'une
    // AUTRE langue.
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()
    private fun applyLanguageFilter(list: List<Movie>): List<Movie> =
        if (contentLanguage == null) list
        else list.filter { val c = extractLeadingLanguageCode(it.category); c == null || c == contentLanguage }

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel/MoviesViewModel, même principe.
    val selectedCategory = MutableLiveData<String?>(null)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
        addSource(allSeries) { list ->
            value = applyLanguageFilter(list).map { it.category }.filter { it.isNotBlank() }.distinct()
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
                series = applyLanguageFilter(series)
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
