package com.nicotv.iptv2.ui.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Channel
import kotlinx.coroutines.launch

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    val favorites = repository.getFavoriteMoviesAndSeries().asLiveData()

    // Chaînes favorites (29/08/2026, bug corrigé : jamais interrogées ici
    // jusqu'ici, une chaîne mise en favori n'apparaissait donc nulle part
    // sur cet écran).
    val favoriteChannels = repository.getFavoriteChannels().asLiveData()

    fun toggleChannelFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel.id, FavoriteEntity.Type.CHANNEL, channel.isFavorite)
        }
    }
}
