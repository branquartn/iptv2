package com.nicotv.iptv.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.network.model.TmdbCastMember
import com.nicotv.iptv.data.network.model.TmdbCrewMember
import com.nicotv.iptv.data.network.model.TmdbMultiResult
import com.nicotv.iptv.data.network.model.TmdbPerson
import com.nicotv.iptv.domain.model.Movie
import com.nicotv.iptv.domain.model.OpenTarget
import com.nicotv.iptv.domain.model.SimilarWork
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.mediaRepository

    private val _movie = MutableLiveData<Movie?>()
    val movie: LiveData<Movie?> = _movie

    private val _resumePosition = MutableLiveData(0L)
    val resumePosition: LiveData<Long> = _resumePosition

    private val _searchResults = MutableLiveData<List<TmdbMultiResult>>(emptyList())
    val searchResults: LiveData<List<TmdbMultiResult>> = _searchResults

    private val _isSearching = MutableLiveData(false)
    val isSearching: LiveData<Boolean> = _isSearching

    private val _relinkDone = MutableLiveData(false)
    val relinkDone: LiveData<Boolean> = _relinkDone

    private val _cast = MutableLiveData<List<TmdbCastMember>>(emptyList())
    val cast: LiveData<List<TmdbCastMember>> = _cast

    private val _director = MutableLiveData<TmdbCrewMember?>(null)
    val director: LiveData<TmdbCrewMember?> = _director

    private val _similar = MutableLiveData<List<SimilarWork>>(emptyList())
    val similar: LiveData<List<SimilarWork>> = _similar

    // Message à afficher (Toast) après un ajout depuis la filmographie/similaires
    // (même flux que SearchViewModel.addToServer — l'appli a déjà un écran
    // « Ajouter » indépendant, cf. SearchActivity).
    private val _addResult = MutableLiveData<String?>(null)
    val addResult: LiveData<String?> = _addResult

    private var extrasLoadedFor = -1

    /** Casting, réalisateur, films similaires — une seule fois par film (pas à
     * chaque onResume : évite de re-fetch credits/recommendations pour rien). */
    fun loadExtras(tmdbId: Int) {
        if (tmdbId <= 0 || extrasLoadedFor == tmdbId) return
        extrasLoadedFor = tmdbId
        viewModelScope.launch {
            val credits = repository.getMovieCredits(tmdbId)
            _cast.value = credits.cast.filter { !it.profilePath.isNullOrBlank() }.take(20)
            _director.value = credits.crew.firstOrNull { it.job == "Director" }
            val username = app.sessionManager.getUsername()
            _similar.value = repository.getMovieRecommendations(tmdbId).map {
                repository.toSimilarWork(
                    it.id, isTv = false, it.title, it.releaseDate.take(4), it.posterPath, username,
                    overview = it.overview, backdropPath = it.backdropPath, rating = it.voteAverage
                )
            }
        }
    }

    /** Appel direct (pas de LiveData) : un clic = une action, pas d'ambiguïté entre
     * « pas encore chargé » et « aucune bande-annonce trouvée » (les deux seraient
     * `null` dans une LiveData réutilisée à chaque clic). */
    suspend fun loadTrailerKey(tmdbId: Int): String? =
        if (tmdbId <= 0) null else repository.getMovieTrailerKey(tmdbId)

    /** Même chose, mais pour une carte (aperçu films similaires/filmographie) qui
     * peut être un film OU une série. */
    suspend fun loadTrailerKeyFor(tmdbId: Int, isTv: Boolean): String? =
        if (tmdbId <= 0) null else repository.getTrailerKey(tmdbId, isTv)

    /** Genres + durée pour l'aperçu (films similaires/filmographie) — mêmes infos
     * que la fiche film normale. */
    suspend fun loadWorkGenresAndRuntime(tmdbId: Int, isTv: Boolean): Pair<String, Int> =
        if (tmdbId <= 0) "" to 0 else repository.getWorkGenresAndRuntime(tmdbId, isTv)

    /** Fiche + filmographie d'un acteur (appel direct, pas de LiveData : dialog
     * transitoire, pas besoin de survivre à une rotation d'écran). */
    suspend fun loadPerson(personId: Int): TmdbPerson? = repository.getPersonDetail(personId)

    suspend fun loadPersonFilmography(personId: Int): List<SimilarWork> =
        repository.getPersonFilmographyAsWork(personId, app.sessionManager.getUsername())

    /** Fiche réalisateur : films réalisés (crew job=Director), pas la filmographie d'acteur. */
    suspend fun loadPersonDirected(personId: Int): List<SimilarWork> =
        repository.getPersonDirectedAsWork(personId, app.sessionManager.getUsername())

    /** Déjà possédé → cible de navigation ; sinon ajoute à la file de téléchargement
     * (identique à SearchActivity/addToServer) et renvoie null (le Toast passe par
     * _addResult, observé dans DetailActivity). */
    suspend fun resolveOrAdd(work: SimilarWork): OpenTarget? =
        repository.resolveOrAddWork(work, app.sessionManager.getUsername(), app.sessionManager.bearer()) {
            _addResult.value = it
        }

    fun loadMovie(movieId: Long) {
        viewModelScope.launch {
            _movie.value = repository.getMovieById(movieId)
            _resumePosition.value = repository.getWatchPosition(movieId)
            // Ouvrir la fiche retire le badge « NOUVEAU » (les listes se mettent
            // à jour via la LiveData de Room) — et synchronise avec la PWA
            // (seen.mkeys, comme markItemSeen() côté PWA).
            repository.markMovieSeen(movieId, app.sessionManager.getUsername(), app.sessionManager.bearer())
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            _movie.value?.let {
                repository.toggleFavorite(it.id, app.sessionManager.getUsername(), app.sessionManager.bearer())
                _movie.value = repository.getMovieById(it.id)
            }
        }
    }

    fun searchTmdb(query: String) {
        if (query.isBlank()) return
        _isSearching.value = true
        _searchResults.value = emptyList()
        viewModelScope.launch {
            try {
                _searchResults.value = repository.searchTmdb(query)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun relinkMovie(result: TmdbMultiResult) {
        val id = _movie.value?.id ?: return
        viewModelScope.launch {
            repository.relinkMovieToTmdb(id, result)
            _movie.value = repository.getMovieById(id)
            _searchResults.value = emptyList()
            _relinkDone.value = true
            _relinkDone.value = false
        }
    }
}
