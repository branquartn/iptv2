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
        // ⚠️ Calcul déporté en Dispatchers.Default (corrigé 29/08/2026) — cf.
        // MoviesViewModel.categories, même freeze main thread constaté sur
        // l'écran Séries.
        addSource(allSeries) { list ->
            viewModelScope.launch {
                val result = withContext(Dispatchers.Default) {
                    applyLanguageFilter(list).map { it.category }.filter { it.isNotBlank() }.distinct()
                        .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
                }
                value = result
            }
        }
    }

    // ⚠️ Recherche par titre en SQL — cf. MoviesViewModel.filteredMovies, même
    // raison et même principe (repository.searchSeriesByTitle).
    // ⚠️ Debounce seulement si recherche en cours — cf. MoviesViewModel.
    // filteredMovies, même correctif (le delay(150) s'appliquait aussi à
    // l'ouverture de l'écran, "recharge tout" perçu à chaque retour).
    val filteredSeries: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                val query = searchQuery.value.orEmpty().trim()
                if (query.isNotBlank()) delay(150)
                // ⚠️ Déporté en Dispatchers.Default (corrigé 29/08/2026) — cf.
                // MoviesViewModel.filteredMovies, même correctif.
                val series = withContext(Dispatchers.Default) {
                    var s = if (query.isBlank()) allSeries.value ?: emptyList()
                            else repository.searchSeriesByTitle(query)
                    s = applyLanguageFilter(s)
                    selectedCategory.value?.let { cat -> s = s.filter { it.category == cat } }
                    s
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
