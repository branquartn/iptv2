package com.nicotv.iptv2.ui.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.util.foldAccents
import com.nicotv.iptv2.util.isFrenchLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allChannels = repository.getChannels().asLiveData()

    val searchQuery = MutableLiveData("")
    val selectedCategory = MutableLiveData<String?>(null)
    val favoritesOnly = MutableLiveData(false)
    val frenchOnly = MutableLiveData(false)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        addSource(allChannels) { list ->
            // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
            value = list.map { it.category }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
        }
    }

    // ⚠️ Filtre en coroutine debouncée — cf. MoviesViewModel.filteredMovies,
    // même raison (foldAccents() sur tout le catalogue à chaque frappe, en
    // synchrone sur le thread principal — ici jusqu'à ~47 000 chaînes).
    val filteredChannels: LiveData<List<Channel>> = MediatorLiveData<List<Channel>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                var base = allChannels.value ?: return@launch
                if (favoritesOnly.value == true) base = base.filter { it.isFavorite }
                selectedCategory.value?.let { cat -> base = base.filter { it.category == cat } }
                val onlyFrench = frenchOnly.value == true
                val query = searchQuery.value.orEmpty().trim()
                if (onlyFrench || query.isNotBlank()) {
                    val queryFolded = query.foldAccents()
                    base = withContext(Dispatchers.Default) {
                        base.filter { channel ->
                            (!onlyFrench || isFrench(channel)) &&
                                (query.isBlank() || channel.name.foldAccents().contains(queryFolded, ignoreCase = true))
                        }
                    }
                }
                value = base
            }
        }
        addSource(allChannels) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
        addSource(favoritesOnly) { filter() }
        addSource(frenchOnly) { filter() }
    }

    /** Heuristique "France" (cf. util.isFrenchLabel) appliquée nom+catégorie —
     * plus permissive que le tri des catégories (qui ne regarde que la
     * catégorie seule) : une chaîne FR peut être classée dans une catégorie au
     * nom neutre. */
    private fun isFrench(channel: Channel): Boolean = isFrenchLabel("${channel.category} ${channel.name}")

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel.id, FavoriteEntity.Type.CHANNEL, channel.isFavorite)
        }
    }

    /** Mini-guide "en cours/à suivre" d'une chaîne — cf. ChannelAdapter (appelé
     * au bind d'une ligne, résultat mis en cache par le repository). */
    suspend fun getShortEpg(channel: Channel) = repository.getShortEpg(channel)
}
