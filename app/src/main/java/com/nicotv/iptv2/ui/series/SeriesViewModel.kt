package com.nicotv.iptv2.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.repository.PlaylistRepository
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ Contrairement à Films et Chaînes, cet écran s'ouvre toujours sur
 * "Toutes" : la demande du 30/08/2026 ne visait qu'eux ("dans films va
 * directement dans FR - LE DERNIER AJOUTE... et pareil pour les chaînes").
 * Si une catégorie par défaut devient souhaitable ici aussi, reprendre
 * util.pickDefaultCategory + le garde-fou awaitingDefaultCategory de
 * MoviesViewModel plutôt qu'improviser.
 *
 * ⚠️ Réécrit en pagination le 30/08/2026 — strictement le même patron que
 * [com.nicotv.iptv2.ui.movies.MoviesViewModel] (lire son en-tête pour le
 * pourquoi complet : mapper tout le catalogue en mémoire avant affichage
 * coûtait des dizaines de secondes de CPU sur un gros panel). Séries n'avait
 * pas été traité dans le premier lot (portée volontairement limitée à Films),
 * étendu ici sur demande explicite : "faire pareil pour série et live".
 *
 * Pagination sur "Toutes" uniquement ; une catégorie précise est chargée en
 * entier (cf. pageLimitFor). Recherche texte non paginée (déjà bornée à 200
 * côté SQL).
 */
class SeriesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel/MoviesViewModel, même principe.
    val selectedCategory = MutableLiveData<String?>(null)

    private val _categories = MutableLiveData<List<String>>(emptyList())
    val categories: LiveData<List<String>> = _categories

    private val _isReady = MutableLiveData(false)
    val isReady: LiveData<Boolean> = _isReady

    private val _isLoadingMore = MutableLiveData(false)
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore

    private var pagingOffset = 0
    private var endReached = false
    private var isSearching = false

    /** Cf. MoviesViewModel.loadGeneration. */
    private var loadGeneration = 0

    private val _series = MediatorLiveData<List<Movie>>()
    val series: LiveData<List<Movie>> = _series

    /** Cf. MoviesViewModel.pageLimitFor — pagination sur "Toutes" seulement. */
    private fun pageLimitFor(category: String?): Int =
        if (category == null) PlaylistRepository.MOVIES_PAGE_SIZE else PlaylistRepository.NO_LIMIT

    /** Appliqué UNIQUEMENT aux résultats de recherche (≤200 lignes) — le
     * chemin sans recherche filtre déjà langue/catégorie en SQL, cf.
     * SeriesDao.getSeriesPage. */
    private fun applyLanguageFilter(list: List<Movie>): List<Movie> =
        if (contentLanguage == null) list
        else list.filter { val c = extractLeadingLanguageCode(it.category); c == null || c == contentLanguage }

    init {
        loadCategories()

        var job: Job? = null
        fun load() {
            job?.cancel()
            pagingOffset = 0
            endReached = false
            _isReady.value = false
            job = viewModelScope.launch {
                val query = searchQuery.value.orEmpty().trim()
                if (query.isNotBlank()) delay(150)
                val cat = selectedCategory.value
                val result = withContext(Dispatchers.Default) {
                    if (query.isNotBlank()) {
                        var s = repository.searchSeriesByTitle(query)
                        s = applyLanguageFilter(s)
                        // Catégorie BRUTE (30/08/2026 : plus de renommage,
                        // le préfixe "FR - " reste affiché).
                        cat?.let { c -> s = s.filter { it.category == c } }
                        s
                    } else {
                        repository.getSeriesPage(contentLanguage, cat, offset = 0, limit = pageLimitFor(cat))
                    }
                }
                isSearching = query.isNotBlank()
                pagingOffset = result.size
                endReached = isSearching || cat != null || result.size < PlaylistRepository.MOVIES_PAGE_SIZE
                _series.value = result
                _isReady.value = true
            }
        }
        _series.addSource(searchQuery) { load() }
        _series.addSource(selectedCategory) { load() }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                // Ordre de la playlist — cf. MoviesViewModel.loadCategories.
                repository.getSeriesCategories(contentLanguage).filter { it.isNotBlank() }
            }
            _categories.value = result
        }
    }

    /** Cf. MoviesViewModel.loadNextPage. */
    fun loadNextPage() {
        if (isSearching || endReached || _isLoadingMore.value == true) return
        _isLoadingMore.value = true
        val generation = loadGeneration
        viewModelScope.launch {
            val page = withContext(Dispatchers.Default) {
                repository.getSeriesPage(contentLanguage, selectedCategory.value, offset = pagingOffset, limit = PlaylistRepository.MOVIES_PAGE_SIZE)
            }
            // Le filtre a changé pendant la requête : cette page appartient à
            // un état révolu, l'ajouter mélangerait deux résultats.
            if (generation != loadGeneration) {
                _isLoadingMore.value = false
                return@launch
            }
            val current = _series.value ?: emptyList()
            // Filet anti-doublons : l'ordre SQL est désormais total (cf.
            // MovieDao.getMoviesPage) donc une page ne devrait plus jamais
            // recouper la précédente — mais si ça arrivait, mieux vaut afficher
            // moins que deux fois la même affiche.
            val known = current.mapTo(HashSet(current.size)) { it.id }
            val fresh = page.filterNot { it.id in known }
            pagingOffset += page.size
            if (page.size < PlaylistRepository.MOVIES_PAGE_SIZE) endReached = true
            if (fresh.isNotEmpty()) _series.value = current + fresh
            _isLoadingMore.value = false
        }
    }

    /** Cf. MoviesViewModel.refreshFavoriteStates — appelé par
     * SeriesActivity.onResume (la pagination n'est plus réactive à la table
     * favoris, contrairement à l'ancien seriesFlow). */
    fun refreshFavoriteStates() {
        val current = _series.value
        if (current.isNullOrEmpty()) return
        viewModelScope.launch {
            val favIds = repository.getFavoriteSeriesIds()
            // ⚠️ Ne reconstruit la liste QUE si un état a réellement changé
            // (audit perf 30/08/2026) : cette méthode tourne à CHAQUE retour
            // depuis la fiche détail, et une catégorie chargée en entier peut
            // compter des milliers d'entrées — les recopier toutes (puis tout
            // réafficher) pour un aller-retour sans changement de favori était
            // du gaspillage pur, en plus de perturber le focus D-pad.
            if (current.none { (it.id in favIds) != it.isFavorite }) return@launch
            _series.value = current.map { it.copy(isFavorite = it.id in favIds) }
        }
    }

    fun toggleFavorite(series: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(series.id, FavoriteEntity.Type.SERIES, series.isFavorite)
            refreshFavoriteStates()
        }
    }
}
