package com.nicotv.iptv2.ui.movies

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

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allMovies = repository.getMovies().asLiveData()

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel, même principe de filtre par catégorie
    // (sidebar gauche, comme IPTV Smarters Pro).
    val selectedCategory = MutableLiveData<String?>(null)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
        addSource(allMovies) { list ->
            value = list.map { it.category }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
        }
    }

    // ⚠️ Recherche par titre en SQL (repository.searchMoviesByTitle), pas en
    // filtrant getMovies().value en Kotlin (corrigé 28/08/2026) : même sur un
    // thread de fond, filtrer ~136 000 titres avec foldAccents() (Normalizer)
    // par frappe restait perceptiblement plus lent que l'écran Recherche
    // global (déjà en SQL) — d'où le "pas immédiat" signalé en comparaison.
    // Catégorie appliquée en Kotlin ensuite, sur le résultat déjà réduit par
    // le SQL (ou sur le catalogue complet si pas de recherche en cours) —
    // jamais sur les 136 000 lignes à la fois. Debounce (150ms) pour ne pas
    // lancer une requête par caractère tapé rapidement.
    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                val query = searchQuery.value.orEmpty().trim()
                var movies = if (query.isBlank()) allMovies.value ?: emptyList()
                             else repository.searchMoviesByTitle(query)
                selectedCategory.value?.let { cat -> movies = movies.filter { it.category == cat } }
                value = movies
            }
        }
        addSource(allMovies) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
    }

    fun toggleFavorite(movie: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(movie.id, FavoriteEntity.Type.MOVIE, movie.isFavorite)
        }
    }
}
