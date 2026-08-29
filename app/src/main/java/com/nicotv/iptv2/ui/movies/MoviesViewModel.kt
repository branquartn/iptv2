package com.nicotv.iptv2.ui.movies

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
import com.nicotv.iptv2.util.MOVIES_PREFERRED_CATEGORIES
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import com.nicotv.iptv2.util.isFrenchLabel
import com.nicotv.iptv2.util.pickDefaultCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ Réécrit en pagination le 29/08/2026 (demande explicite, "encore pire...
 * comme si rien n'était en cache") — cf. CLAUDE.md ("Pagination écran Films")
 * pour l'historique complet des correctifs qui ont précédé celui-ci (tous
 * insuffisants seuls). L'ancienne version mappait la TOTALITÉ du catalogue
 * (jusqu'à ~136 000 films sur un gros panel Xtream) en objets domaine à
 * CHAQUE ouverture de l'écran avant de pouvoir afficher quoi que ce soit —
 * même hors thread principal (Dispatchers.Default), ce travail seul prenait
 * plusieurs dizaines de secondes (confirmé par instrumentation logcat réelle :
 * GC libérant plus de 100 Mo par passe). Cette version charge le catalogue
 * PAR PAGES (repository.getMoviesPage, filtré en SQL par langue/catégorie —
 * cf. MovieEntity.languageCode/categoryStripped, calculés une fois au
 * chargement de la playlist plutôt que recalculés à chaque écran) : la
 * première page (PlaylistRepository.MOVIES_PAGE_SIZE films) s'affiche quasi
 * instantanément, la suite arrive au scroll (MoviesActivity.loadNextPage).
 *
 * La recherche texte reste sur l'ancien principe (repository.
 * searchMoviesByTitle, déjà limité à 200 résultats et déjà rapide, cf.
 * CLAUDE.md) — un résultat déjà borné n'a pas besoin d'être paginé en plus,
 * et le filtre langue/catégorie qui suit une recherche reste en Kotlin
 * (appliqué sur ≤200 lignes, pas 136 000 — coût négligible).
 *
 * ⚠️ Portée volontairement limitée à l'écran Films (pas Séries/Chaînes) —
 * le signalement portait spécifiquement sur Films, et refaire les trois
 * d'un coup sans pouvoir builder/tester triplait le risque pour un gain non
 * demandé ailleurs. Si Séries/Chaînes deviennent douloureux sur un très gros
 * catalogue, reprendre le même principe (colonnes languageCode/
 * categoryStripped + DAO paginé) plutôt qu'inventer autre chose.
 */
class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    private val contentLanguage = app.contentLanguagePrefs.getLanguage()

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel, même principe de filtre par catégorie
    // (sidebar gauche, comme IPTV Smarters Pro).
    val selectedCategory = MutableLiveData<String?>(null)

    private val _categories = MutableLiveData<List<String>>(emptyList())
    val categories: LiveData<List<String>> = _categories

    // ⚠️ Distingue "pas encore chargé" de "vraiment vide" — même principe que
    // PlaylistRepository.isMoviesReady (écrans Séries/Chaînes), appliqué ici
    // directement sur le chargement de la première page plutôt que sur le
    // catalogue complet.
    private val _isReady = MutableLiveData(false)
    val isReady: LiveData<Boolean> = _isReady

    // Vrai pendant le chargement d'une page suivante (scroll) — distinct de
    // isReady (qui ne concerne que la toute première page). MoviesActivity
    // peut s'en servir pour un petit indicateur de bas de liste si besoin.
    private val _isLoadingMore = MutableLiveData(false)
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore

    // État de pagination interne — jamais exposé, MoviesActivity ne connaît
    // que loadNextPage()/movies/isReady.
    private var pagingOffset = 0
    private var endReached = false
    private var isSearching = false

    private fun applyLanguageFilter(list: List<Movie>): List<Movie> =
        if (contentLanguage == null) list
        else list.filter { val c = extractLeadingLanguageCode(it.category); c == null || c == contentLanguage }

    private val _movies = MediatorLiveData<List<Movie>>()
    val movies: LiveData<List<Movie>> = _movies

    /** Pagination seulement sur "Toutes" (30/08/2026, demande explicite :
     * "ça se limite à 60 pour Toutes, mais pour chaque catégorie tu peux tout
     * charger") — une catégorie précise est toujours bien plus petite que le
     * catalogue complet, donc chargée en entier d'un coup : compteur juste
     * immédiatement et scroll complet sans attendre de page suivante. */
    private fun pageLimitFor(category: String?): Int =
        if (category == null) PlaylistRepository.MOVIES_PAGE_SIZE else PlaylistRepository.NO_LIMIT

    private var job: Job? = null

    // ⚠️ Bloque le tout premier chargement le temps que la catégorie par
    // défaut soit connue (30/08/2026, demande explicite : ouvrir directement
    // sur "FR - LE DERNIER AJOUTE", jamais sur "Toutes"). Sans ce garde-fou,
    // l'écran chargerait d'abord "Toutes" (le cas le plus lourd, tout le
    // catalogue paginé) avant de tout jeter pour recharger la catégorie —
    // deux chargements, dont le plus coûteux pour rien.
    private var awaitingDefaultCategory = true

    init {
        _movies.addSource(searchQuery) { reload() }
        _movies.addSource(selectedCategory) { reload() }
        loadCategories()
    }

    private fun reload() {
        if (awaitingDefaultCategory) return
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
                    var m = repository.searchMoviesByTitle(query)
                    m = applyLanguageFilter(m)
                    // Comparaison sur la catégorie BRUTE (30/08/2026 : plus de
                    // renommage, le préfixe "FR - " reste affiché) — cf.
                    // MovieDao.getDistinctCategoriesForLanguage.
                    cat?.let { c -> m = m.filter { it.category == c } }
                    m
                } else {
                    repository.getMoviesPage(contentLanguage, cat, offset = 0, limit = pageLimitFor(cat))
                }
            }
            isSearching = query.isNotBlank()
            pagingOffset = result.size
            // Rien de plus à paginer si : recherche (résultat déjà borné à
            // 200), catégorie précise (tout a été chargé d'un coup, cf.
            // pageLimitFor), ou page incomplète (fin du catalogue).
            endReached = isSearching || cat != null || result.size < PlaylistRepository.MOVIES_PAGE_SIZE
            _movies.value = result
            _isReady.value = true
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            // ⚠️ try/catch obligatoire : awaitingDefaultCategory bloque TOUT
            // chargement tant qu'il est vrai — si cette lecture échouait sans
            // être rattrapée, l'écran resterait figé sur le spinner pour de
            // bon. En cas d'échec on repart sur "Toutes" (aucune catégorie).
            val result = try {
                withContext(Dispatchers.Default) {
                    repository.getMoviesCategories(contentLanguage)
                        .filter { it.isNotBlank() }
                        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
                        .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
                }
            } catch (e: Exception) {
                emptyList()
            }
            _categories.value = result

            // Catégorie ouverte par défaut — cf. util.pickDefaultCategory.
            val default = pickDefaultCategory(result, MOVIES_PREFERRED_CATEGORIES)
            awaitingDefaultCategory = false
            if (default != null) {
                // Déclenche reload() via addSource(selectedCategory).
                selectedCategory.value = default
            } else {
                // Aucune catégorie dans ce catalogue : on reste sur "Toutes",
                // mais il faut relancer le chargement bloqué plus haut.
                reload()
            }
        }
    }

    /** Appelé par MoviesActivity quand le scroll approche de la fin de la
     * liste déjà chargée — no-op pendant une recherche (résultat déjà
     * complet, ≤200 lignes), une fois la dernière page atteinte, ou si un
     * chargement est déjà en cours. */
    fun loadNextPage() {
        if (isSearching || endReached || _isLoadingMore.value == true) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            val page = withContext(Dispatchers.Default) {
                repository.getMoviesPage(contentLanguage, selectedCategory.value, offset = pagingOffset, limit = PlaylistRepository.MOVIES_PAGE_SIZE)
            }
            pagingOffset += page.size
            if (page.size < PlaylistRepository.MOVIES_PAGE_SIZE) endReached = true
            _movies.value = (_movies.value ?: emptyList()) + page
            _isLoadingMore.value = false
        }
    }

    /** Appelé par MoviesActivity.onResume — cf. PlaylistRepository.
     * getFavoriteMovieIds : la pagination n'est plus un Flow réactif à la
     * table favoris comme l'était l'ancienne version (moviesFlow), donc un
     * favori togglé depuis la fiche détail ne se répercute plus tout seul
     * sur la grille déjà affichée sans cet appel explicite. */
    fun refreshFavoriteStates() {
        val current = _movies.value
        if (current.isNullOrEmpty()) return
        viewModelScope.launch {
            val favIds = repository.getFavoriteMovieIds()
            _movies.value = current.map { it.copy(isFavorite = it.id in favIds) }
        }
    }

    fun toggleFavorite(movie: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(movie.id, FavoriteEntity.Type.MOVIE, movie.isFavorite)
            refreshFavoriteStates()
        }
    }
}
