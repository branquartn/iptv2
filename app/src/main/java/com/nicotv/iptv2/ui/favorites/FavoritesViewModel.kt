package com.nicotv.iptv2.ui.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    val favorites = repository.getFavoriteMoviesAndSeries().asLiveData()
}
