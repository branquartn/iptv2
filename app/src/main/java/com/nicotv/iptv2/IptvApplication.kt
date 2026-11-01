package com.nicotv.iptv2

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nicotv.iptv2.data.ContentLanguagePrefs
import com.nicotv.iptv2.data.PlaylistSourcePrefs
import com.nicotv.iptv2.data.database.AppDatabase
import com.nicotv.iptv2.data.repository.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class IptvApplication : Application(), ImageLoaderFactory {

    // Survit à la destruction d'une Activity/ViewModel — utilisé pour les
    // écritures qui doivent finir même si l'écran qui les a déclenchées se ferme
    // juste après (sauvegarde de la position de lecture au retour du player).
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this) }
    val sourcePrefs by lazy { PlaylistSourcePrefs(this) }
    val contentLanguagePrefs by lazy { ContentLanguagePrefs(this) }

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
        PlaylistRepository(this, database, okHttpClient, sourcePrefs, appScope).also { repo ->
            // Films / séries autorisés par l'admin pour le profil chargé.
            repo.contentFlags = { id -> sessionManager.getContentFlags(id) }
            repo.serverToken = { sessionManager.getToken() }
            repo.onFlagsApplied = { id, flags -> sessionManager.setLoadedFlags(id, flags) }
        }
    }

    // Compte NicoTV (même login que l'app iptv) + codes Xtream gérés à distance par l'admin.
    val sessionManager by lazy { com.nicotv.iptv2.account.SessionManager(this) }
    val accountApi by lazy { com.nicotv.iptv2.account.AccountApi(okHttpClient, sessionManager) }
    val remoteSources by lazy {
        com.nicotv.iptv2.account.RemoteSources(accountApi, sessionManager, playlistRepository, sourcePrefs).also { rs ->
            // L'admin a changé la playlist active (identifiants ou films/séries) : rechargement en arrière-plan.
            rs.onActiveProfileChanged = { id, name -> catalogLoader.start(id, name) }
            rs.catalogReloadRunning = { catalogLoader.isRunning }
        }
    }
    // Tchat NicoTV ↔ Telegram (mêmes routes et mêmes comptes que l'app iptv).
    val chatApi by lazy { com.nicotv.iptv2.chat.ChatApi(okHttpClient, sessionManager, this) }
    // Chargement de la playlist en arrière-plan, avec pourcentage (bandeau en haut de chaque écran).
    val catalogLoader by lazy { com.nicotv.iptv2.account.CatalogLoader(this) }

    // Config Coil explicite (défaut sinon non borné en usage réel : mur
    // d'affiches films/séries/chaînes potentiellement énorme sur un gros
    // panel Xtream). 300 Mo de cache disque, 25% de la RAM dispo en mémoire —
    // vidables depuis l'écran Réglages (ImageCacheUtil.clear).
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .build()
}
