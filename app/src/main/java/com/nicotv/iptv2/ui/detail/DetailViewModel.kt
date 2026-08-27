package com.nicotv.iptv2.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Movie
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val _movie = MutableLiveData<Movie?>()
    val movie: LiveData<Movie?> = _movie

    private val _resumePositionMs = MutableLiveData(0L)
    val resumePositionMs: LiveData<Long> = _resumePositionMs

    fun load(movieId: Long) {
        viewModelScope.launch {
            val m = repository.getMovieById(movieId)
            _movie.value = m
            _resumePositionMs.value = if (m != null) repository.getWatchPosition("m$movieId") else 0L
        }
    }

    fun toggleFavorite() {
        val m = _movie.value ?: return
        viewModelScope.launch {
            repository.toggleFavorite(m.id, FavoriteEntity.Type.MOVIE, m.isFavorite)
            _movie.value = m.copy(isFavorite = !m.isFavorite)
        }
    }
}
