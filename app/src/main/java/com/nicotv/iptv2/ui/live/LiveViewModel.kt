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
import kotlinx.coroutines.launch
import kotlinx.coroutines.viewModelScope

class LiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allChannels = repository.getChannels().asLiveData()

    val searchQuery = MutableLiveData("")
    val selectedCategory = MutableLiveData<String?>(null)
    val favoritesOnly = MutableLiveData(false)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        addSource(allChannels) { list -> value = list.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
    }

    val filteredChannels: LiveData<List<Channel>> = MediatorLiveData<List<Channel>>().apply {
        fun filter() {
            var base = allChannels.value ?: return
            if (favoritesOnly.value == true) base = base.filter { it.isFavorite }
            selectedCategory.value?.let { cat -> base = base.filter { it.category == cat } }
            val query = searchQuery.value.orEmpty().trim().foldAccents()
            value = if (query.isBlank()) base else base.filter { it.name.foldAccents().contains(query, ignoreCase = true) }
        }
        addSource(allChannels) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
        addSource(favoritesOnly) { filter() }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel.id, FavoriteEntity.Type.CHANNEL, channel.isFavorite)
        }
    }
}
