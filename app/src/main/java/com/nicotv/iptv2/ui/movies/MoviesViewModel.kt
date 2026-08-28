package com.nicotv.iptv2.ui.movies

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.ContentLanguagePrefs
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

    // Réglage persistant (Réglages > Langue du contenu, 28/08/2026) — lu une
    // fois à l'ouverture de l'écran (nouveau ViewModel à chaque visite, cf.
    // CLAUDE.md), pas besoin d'être réactif en cours d'écran. Même heuristique
    // que le filtre FR de l'écran Chaînes (util.isFrenchLabel), appliquée ici
    // à TOUT le catalogue avant catégories/recherche, pas juste sur un bouton
    // par écran.
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()
    private fun applyLanguageFilter(list: List<Movie>): List<Movie> =
        if (contentLanguage == ContentLanguagePrefs.FRENCH) {
            list.filter { isFrenchLabel(it.title) || isFrenchLabel(it.category) }
        } else list

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel, même principe de filtre par catégorie
    // (sidebar gauche, comme IPTV Smarters Pro).
    val selectedCategory = MutableLiveData<String?>(null)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
        // Calculées sur le catalogue déjà filtré par langue : pas de catégorie
        // 100% non-FR listée si "Français uniquement" est actif, elle donnerait
        // toujours zéro résultat une fois sélectionnée.
        addSource(allMovies) { list ->
            value = applyLanguageFilter(list).map { it.category }.filter { it.isNotBlank() }.distinct()
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
                movies = applyLanguageFilter(movies)
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
