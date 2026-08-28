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
import com.nicotv.iptv2.util.isFrenchLabel
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    private val allMovies = repository.getMovies().asLiveData()

    val searchQuery = MutableLiveData("")
    // null = "Toutes" — cf. LiveViewModel, même principe de filtre par catégorie
    // (sidebar gauche, comme IPTV Smarters Pro).
    val selectedCategory = MutableLiveData<String?>(null)

    val categories: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        // Catégories France en premier (demande explicite) — cf. isFrenchLabel.
        addSource(allMovies) { list ->
            value = list.map { it.category }.filter { it.isNotBlank() }.distinct()
                .sortedWith(compareByDescending<String> { isFrenchLabel(it) }.thenBy { it })
        }
    }

    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        fun filter() {
            var movies = allMovies.value ?: return
            selectedCategory.value?.let { cat -> movies = movies.filter { it.category == cat } }
            val query = searchQuery.value.orEmpty().trim()
            val queryFolded = query.foldAccents()
            value = if (query.isBlank()) movies
                    else movies.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
        }
        addSource(allMovies) { filter() }
        addSource(searchQuery) { filter() }
        addSource(selectedCategory) { filter() }
    }

    fun toggleFavorite(movie: Movie) {
        viewModelScope.launch {
            repository.toggleFavorite(movie.id, FavoriteEntity.Type.MOVIE, movie.isFavorite)
        }
    }
}
