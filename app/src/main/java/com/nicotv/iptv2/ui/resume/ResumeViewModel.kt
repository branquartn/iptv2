package com.nicotv.iptv2.ui.resume

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.domain.model.Movie

class ResumeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    private val repository = app.playlistRepository

    val resumeMovies: LiveData<List<Movie>> = repository.getUnifiedHistory().asLiveData()
}
