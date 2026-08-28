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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ⚠️ Filtre en coroutine debouncée (Dispatchers.Default), pas en synchrone sur
    // le thread principal (corrigé 28/08/2026) : sur un catalogue de ~136 000
    // films, filtrer par catégorie + appliquer foldAccents() (Normalizer, coûteux)
    // sur CHAQUE titre à CHAQUE frappe, en direct dans le callback MediatorLiveData
    // (donc sur le thread principal), saccadait l'app pendant la saisie — perçu
    // comme "long et ça bug". Le debounce (150ms) évite en plus de lancer ce
    // travail pour chaque caractère tapé rapidement.
    val filteredMovies: LiveData<List<Movie>> = MediatorLiveData<List<Movie>>().apply {
        var job: Job? = null
        fun filter() {
            job?.cancel()
            job = viewModelScope.launch {
                delay(150)
                var movies = allMovies.value ?: return@launch
                selectedCategory.value?.let { cat -> movies = movies.filter { it.category == cat } }
                val query = searchQuery.value.orEmpty().trim()
                if (query.isNotBlank()) {
                    val queryFolded = query.foldAccents()
                    movies = withContext(Dispatchers.Default) {
                        movies.filter { it.title.foldAccents().contains(queryFolded, ignoreCase = true) }
                    }
                }
                value = movies
            }
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
