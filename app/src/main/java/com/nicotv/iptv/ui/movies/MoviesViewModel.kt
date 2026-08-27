package com.nicotv.iptv.ui.movies

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.domain.model.Movie
import com.nicotv.iptv.util.foldAccents
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.mediaRepository
    private val castCache = app.movieCastCache

    private val username = app.sessionManager.getUsername()
    private val allMovies = repository.getMoviesWithFavorites(username).asLiveData()
    private val isOnline = app.isOnline
    private val downloads = app.downloadRepository.getAllFlow().asLiveData()

    val searchQuery = MutableLiveData("")

    // Rubrique « Nouveautés » (badge accueil, comme new-films côté PWA) : ne
    // montre que les films non encore ouverts (Movie.isNew), recherche possible
    // dedans aussi (comme le hint "Rechercher un nouveau film…" de la PWA).
    val newOnly = MutableLiveData(false)

    // Recherche par titre OU par acteur/réalisateur (cache casting préchauffé en
    // tâche de fond, cf. prefetchCast — comme la recherche PWA/nicotv_castnames).
    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        fun filter() {
            val movies = allMovies.value ?: return
            val query = searchQuery.value.orEmpty().trim()
            var base = if (newOnly.value == true) movies.filter { it.isNew } else movies
            // Hors-ligne (mode avion) : ne montrer que ce qui est jouable sans réseau.
            if (isOnline.value == false) {
                val downloadedIds = downloads.value.orEmpty()
                    .filter { it.type == DownloadEntity.TYPE_MOVIE && it.state == DownloadEntity.STATE_COMPLETED }
                    .mapNotNull { it.key.removePrefix("movie:").toLongOrNull() }
                    .toSet()
                base = base.filter { it.id in downloadedIds }
            }
            val queryFolded = query.foldAccents()
            value = if (query.isBlank()) base
                    else base.filter {
                        it.title.foldAccents().contains(queryFolded, ignoreCase = true) || castCache.matches(it.id, query)
                    }
        }
        addSource(allMovies) { filter() }
        addSource(searchQuery) { filter() }
        addSource(newOnly) { filter() }
        addSource(isOnline) { filter() }
        addSource(downloads) { filter() }
    }

    private var prefetchStarted = false

    /** Préchauffe le cache de casting pour les films pas encore connus, à débit
     * limité (une requête TMDb toutes les ~150ms) pour ne pas la matraquer.
     * Une seule fois par instance de ViewModel (l'écran reste ouvert). */
    fun prefetchCast() {
        if (prefetchStarted) return
        prefetchStarted = true
        viewModelScope.launch {
            val movies = repository.getMoviesWithFavorites(username).first()
            for (m in movies) {
                if (m.tmdbId <= 0 || castCache.isKnown(m.id)) continue
                val credits = repository.getMovieCredits(m.tmdbId)
                val directors = credits.crew.filter { it.job == "Director" }.map { it.name }
                castCache.put(m.id, credits.cast.take(8).map { it.name } + directors)
                delay(150)
            }
        }
    }
}
