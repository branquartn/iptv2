package com.nicotv.iptv.ui.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv.IptvApplication
import kotlinx.coroutines.launch

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    private val repository = app.mediaRepository
    private val username = app.sessionManager.getUsername()
    val favorites = repository.getFavorites(username).asLiveData()

    init {
        viewModelScope.launch {
            runCatching { repository.syncRemoteState(username, app.sessionManager.bearer()) }
        }
    }
}
