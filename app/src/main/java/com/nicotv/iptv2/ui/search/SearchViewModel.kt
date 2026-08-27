package com.nicotv.iptv2.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.domain.model.Movie
import kotlinx.coroutines.launch

data class SearchResults(val movies: List<Movie>, val series: List<Movie>, val channels: List<Channel>) {
    val isEmpty get() = movies.isEmpty() && series.isEmpty() && channels.isEmpty()
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val _results = MutableLiveData(SearchResults(emptyList(), emptyList(), emptyList()))
    val results: LiveData<SearchResults> = _results

    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = SearchResults(emptyList(), emptyList(), emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // anti-rafale pendant la frappe
            val (movies, series, channels) = repository.searchTitle(query)
            _results.value = SearchResults(movies, series, channels)
        }
    }
}
