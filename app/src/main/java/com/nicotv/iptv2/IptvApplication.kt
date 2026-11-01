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
import kotlinx.coroutines.launch
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
    val presenceReporter by lazy { com.nicotv.iptv2.account.PresenceReporter(this, okHttpClient, sessionManager, appScope) }
    val libraryApi by lazy { com.nicotv.iptv2.account.LibraryApi(okHttpClient, sessionManager) }

    // Temps réel (ws.nicotv.ovh), comme l'app iptv : bibliothèque du serveur, codes Xtream de l'admin, tchat.
    val chatEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    // Incrémenté à chaque rafraîchissement de la bibliothèque du serveur : Films / Séries rechargent leur grille.
    val libraryRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile private var librarySyncJob: kotlinx.coroutines.Job? = null
    private val wsClient by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    private val realtimeClient by lazy {
        com.nicotv.iptv2.account.RealtimeClient(wsClient, { sessionManager.getToken() }, appScope,
            onLibraryChanged = { onRealtimeLibraryChange() },
            onXtreamChanged = { appScope.launch { runCatching { remoteSources.sync(force = true) } } },
            onChatEvent = { chatEvents.tryEmit(Unit) },
            onRemote = { data -> handleRemoteCommand(data) })
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Pause / reprise à distance depuis admin.nicotv.ovh. Plusieurs appareils d'un même compte partagent le canal :
     *  on n'obéit que si l'ordre vise CET appareil (même identifiant que celui annoncé dans la présence). Le lecteur doit
     *  être piloté depuis le thread principal : le WebSocket tourne sur un thread de fond. */
    private fun handleRemoteCommand(data: org.json.JSONObject) {
        if (data.optString("deviceId") != com.nicotv.iptv2.util.DeviceId.get(this)) return
        when (data.optString("cmd")) {
            "pause" -> mainHandler.post { com.nicotv.iptv2.player.PlayerActivity.current?.remotePause() }
            "resume" -> mainHandler.post { com.nicotv.iptv2.player.PlayerActivity.current?.remoteResume() }
        }
    }

    fun connectRealtime() { if (sessionManager.isLoggedIn()) realtimeClient.start() }
    fun disconnectRealtime() { realtimeClient.stop() }

    /** Débouncé (rafales d'évènements) ; jamais pendant un chargement complet (il relit déjà la bibliothèque). */
    private fun onRealtimeLibraryChange() {
        if (!sessionManager.isLoggedIn()) return
        librarySyncJob?.cancel()
        librarySyncJob = appScope.launch {
            kotlinx.coroutines.delay(1_500L)
            if (catalogLoader.isRunning) return@launch
            if (runCatching { playlistRepository.refreshServerLibrary() }.getOrDefault(false)) libraryRevision.value++
        }
    }

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
