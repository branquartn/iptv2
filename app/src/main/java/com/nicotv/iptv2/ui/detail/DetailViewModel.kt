package com.nicotv.iptv2.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.tmdb.TmdbCastMember
import com.nicotv.iptv2.data.tmdb.TmdbCrewMember
import com.nicotv.iptv2.data.tmdb.TmdbPerson
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.domain.model.OpenTarget
import com.nicotv.iptv2.domain.model.SimilarWork
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val _movie = MutableLiveData<Movie?>()
    val movie: LiveData<Movie?> = _movie

    private val _resumePositionMs = MutableLiveData(0L)
    val resumePositionMs: LiveData<Long> = _resumePositionMs

    private val _cast = MutableLiveData<List<TmdbCastMember>>(emptyList())
    val cast: LiveData<List<TmdbCastMember>> = _cast

    private val _director = MutableLiveData<TmdbCrewMember?>(null)
    val director: LiveData<TmdbCrewMember?> = _director

    private val _similar = MutableLiveData<List<SimilarWork>>(emptyList())
    val similar: LiveData<List<SimilarWork>> = _similar

    // true dès qu'un match TMDb a été trouvé pour ce titre — pilote la visibilité
    // du bouton bande-annonce (async, résolu dans loadExtras).
    private val _hasTmdbMatch = MutableLiveData(false)
    val hasTmdbMatch: LiveData<Boolean> = _hasTmdbMatch

    // Id TMDb résolu pour ce film (recherche par titre) — 0 tant que loadExtras()
    // n'a pas trouvé de correspondance. Utilisé par le bouton bande-annonce.
    private var tmdbMovieId: Int = 0
    private var extrasLoadedFor = -1L

    fun load(movieId: Long) {
        viewModelScope.launch {
            val m = repository.getMovieById(movieId)
            _movie.value = m
            _resumePositionMs.value = if (m != null) repository.getWatchPosition("m$movieId") else 0L
            // Synopsis Xtream (get_vod_info) — uniquement à l'ouverture de CETTE
            // fiche, jamais pour tout le catalogue (demande explicite). Ne fait
            // rien si le film a déjà un synopsis ou n'est pas issu d'Xtream.
            if (m != null && m.overview.isBlank() && m.xtreamStreamId.isNotBlank()) {
                repository.enrichMovieFromXtreamIfNeeded(movieId)?.let { _movie.value = it }
            }
        }
    }

    fun toggleFavorite() {
        val m = _movie.value ?: return
        viewModelScope.launch {
            repository.toggleFavorite(m.id, FavoriteEntity.Type.MOVIE, m.isFavorite)
            _movie.value = m.copy(isFavorite = !m.isFavorite)
        }
    }

    /** Casting, réalisateur, films similaires — une seule fois par film. Utilise
     * le tmdbId déjà résolu au chargement de la playlist (MovieEntity.tmdbId,
     * cf. PlaylistRepository.enrichMovies) ; ne re-cherche par titre que pour un
     * film chargé avant l'ajout de ce champ (tmdbId=0 en base). */
    fun loadExtras(movieId: Long, movieTmdbId: Int, title: String) {
        if (extrasLoadedFor == movieId) return
        extrasLoadedFor = movieId
        viewModelScope.launch {
            val tmdbId = movieTmdbId.takeIf { it > 0 } ?: repository.resolveTmdbMovieId(title)
            if (tmdbId == null) { _hasTmdbMatch.value = false; return@launch }
            tmdbMovieId = tmdbId
            _hasTmdbMatch.value = true
            val credits = repository.getMovieCredits(tmdbId)
            _cast.value = credits.cast.filter { !it.profilePath.isNullOrBlank() }.take(20)
            _director.value = credits.crew.firstOrNull { it.job == "Director" }
            _similar.value = repository.getMovieRecommendations(tmdbId)
        }
    }

    suspend fun loadTrailerKey(): String? = if (tmdbMovieId <= 0) null else repository.getMovieTrailerKey(tmdbMovieId)

    suspend fun loadTrailerKeyFor(tmdbId: Int, isTv: Boolean): String? =
        if (tmdbId <= 0) null else repository.getWorkTrailerKey(tmdbId, isTv)

    suspend fun loadWorkGenresAndRuntime(tmdbId: Int, isTv: Boolean): Pair<String, Int> =
        if (tmdbId <= 0) "" to 0 else repository.getWorkGenresAndRuntime(tmdbId, isTv)

    suspend fun loadPerson(personId: Int): TmdbPerson? = repository.getPerson(personId)

    suspend fun loadPersonFilmography(personId: Int): List<SimilarWork> = repository.getPersonFilmography(personId)

    suspend fun loadPersonDirected(personId: Int): List<SimilarWork> = repository.getPersonDirected(personId)

    suspend fun resolveTarget(work: SimilarWork): OpenTarget? = repository.resolveOpenTarget(work)
}
