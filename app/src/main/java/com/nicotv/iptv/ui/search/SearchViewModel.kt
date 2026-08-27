package com.nicotv.iptv.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.network.model.TmdbMultiResult
import com.nicotv.iptv.data.network.model.TmdbPerson
import com.nicotv.iptv.domain.model.OpenTarget
import com.nicotv.iptv.domain.model.SimilarWork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val tmdbApi = app.tmdbApi
    private val repository = app.mediaRepository

    // Résultats films/séries en SimilarWork (badge possédé/pas possédé déjà résolu) :
    // grille + fiche aperçu (mobile et TV), comme la PWA (renderAddResults).
    val results = MutableLiveData<List<SimilarWork>>(emptyList())
    // Acteurs trouvés par la recherche (comme la PWA) : clic → fiche/filmographie.
    val people = MutableLiveData<List<TmdbMultiResult>>(emptyList())
    val isLoading = MutableLiveData(false)
    val error = MutableLiveData<String?>(null)

    // Résultat de l'ajout au serveur (message affiché à l'utilisateur).
    val addResult = MutableLiveData<String?>(null)

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            results.value = emptyList()
            people.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(1000) // debounce
            isLoading.value = true
            error.value = null
            try {
                val response = tmdbApi.searchMulti(query)
                val filtered = response.results.filter { it.isMovie || it.isTv }
                people.value = response.results.filter { it.isPerson && it.profileUrl.isNotBlank() }.take(12)
                val username = app.sessionManager.getUsername()
                results.value = filtered.map {
                    repository.toSimilarWork(
                        it.id, it.isTv, it.displayTitle, it.displayYear, it.posterPath, username,
                        overview = it.overview, backdropPath = it.backdropPath, rating = it.voteAverage
                    )
                }
            } catch (e: Exception) {
                error.value = e.localizedMessage
                results.value = emptyList()
                people.value = emptyList()
            } finally {
                isLoading.value = false
            }
        }
    }

    // ── Fiche acteur (même mécanique que DetailViewModel — casting/similaires) ──

    suspend fun loadPerson(personId: Int): TmdbPerson? = repository.getPersonDetail(personId)

    suspend fun loadPersonFilmography(personId: Int): List<SimilarWork> =
        repository.getPersonFilmographyAsWork(personId, app.sessionManager.getUsername())

    /** Fiche réalisateur : films réalisés (crew job=Director), pas la filmographie
     * d'acteur — même logique que DetailViewModel.loadPersonDirected. */
    suspend fun loadPersonDirected(personId: Int): List<SimilarWork> =
        repository.getPersonDirectedAsWork(personId, app.sessionManager.getUsername())

    suspend fun resolveOrAdd(work: SimilarWork): OpenTarget? =
        repository.resolveOrAddWork(work, app.sessionManager.getUsername(), app.sessionManager.bearer()) {
            addResult.value = it
        }

    suspend fun loadTrailerKeyFor(tmdbId: Int, isTv: Boolean): String? =
        if (tmdbId <= 0) null else repository.getTrailerKey(tmdbId, isTv)

    suspend fun loadWorkGenresAndRuntime(tmdbId: Int, isTv: Boolean): Pair<String, Int> =
        if (tmdbId <= 0) "" to 0 else repository.getWorkGenresAndRuntime(tmdbId, isTv)
}
