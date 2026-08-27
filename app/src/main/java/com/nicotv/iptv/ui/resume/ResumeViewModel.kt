package com.nicotv.iptv.ui.resume

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.domain.model.Movie

class ResumeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    private val repository = app.mediaRepository
    private val username = app.sessionManager.getUsername()

    val resumeMovies: LiveData<List<Movie>> = repository.getUnifiedHistory(username).asLiveData()
}
