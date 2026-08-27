package com.nicotv.iptv2.ui.movies

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.util.foldAccents
import kotlinx.coroutines.launch
import kotlinx.coroutines.viewModelScope

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allMovies = repository.getMovies().asLiveData()

    val searchQuery = MutableLiveData("")

    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        fun filter() {
            val movies = allMovies.value ?: return
            val query = searchQuery.value.orEmpty().trim()
            val queryFolded = query.foldAccents()
            value = if (query.isBlank()) movies
                    else movies.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
        }
        addSource(allMovies) { filter() }
        addSource(searchQuery) { filter() }
    }

    fun toggleFavorite(movie: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(movie.id, FavoriteEntity.Type.MOVIE, movie.isFavorite)
        }
    }
}
