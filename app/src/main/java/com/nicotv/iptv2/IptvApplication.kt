package com.nicotv.iptv2

import android.app.Application
import com.nicotv.iptv2.data.PlaylistSourcePrefs
import com.nicotv.iptv2.data.database.AppDatabase
import com.nicotv.iptv2.data.repository.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class IptvApplication : Application() {

    // Survit à la destruction d'une Activity/ViewModel — utilisé pour les
    // écritures qui doivent finir même si l'écran qui les a déclenchées se ferme
    // juste après (sauvegarde de la position de lecture au retour du player).
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this) }
    val sourcePrefs by lazy { PlaylistSourcePrefs(this) }

    // Timeouts généreux : certains panels IPTV/Xtream répondent lentement sous
    // charge (catalogues volumineux, hébergement modeste).
    val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val playlistRepository by lazy {
        PlaylistRepository(this, database, okHttpClient, sourcePrefs)
    }
}
