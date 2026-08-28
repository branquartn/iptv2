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
import kotlinx.coroutines.launch
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

    val filteredChannels: LiveData<List<Channel>> = MediatorLiveData<List<Channel>>().apply {
        fun filter() {
            var base = allChannels.value ?: return
            if (favoritesOnly.value == true) base = base.filter { it.isFavorite }
            if (frenchOnly.value == true) base = base.filter { isFrench(it) }
            selectedCategory.value?.let { cat -> base = base.filter { it.category == cat } }
            val query = searchQuery.value.orEmpty().trim().foldAccents()
            value = if (query.isBlank()) base else base.filter { it.name.foldAccents().contains(query, ignoreCase = true) }
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
