package com.nicotv.iptv.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.database.entity.DownloadEntity

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as IptvApplication
    val downloadRepository = app.downloadRepository

    val downloads: LiveData<List<DownloadEntity>> = app.downloadRepository.getAllFlow().asLiveData()
}
