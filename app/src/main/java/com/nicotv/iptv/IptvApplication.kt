package com.nicotv.iptv

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nicotv.iptv.data.database.AppDatabase
import com.nicotv.iptv.data.network.AuthApi
import com.nicotv.iptv.data.network.CatalogApi
import com.nicotv.iptv.data.network.NicoTvApi
import com.nicotv.iptv.data.network.RealtimeClient
import com.nicotv.iptv.data.network.TmdbApi
import com.nicotv.iptv.data.repository.MediaRepository
import com.nicotv.iptv.download.DownloadRepository
import com.nicotv.iptv.util.FinishedMoviesCache
import com.nicotv.iptv.util.MovieCastCache
import com.nicotv.iptv.util.PresenceScreen
import com.nicotv.iptv.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class IptvApplication : Application() {

    // Pour poster sur le thread UI depuis un callback de fond (ex. RealtimeClient/
    // OkHttp) — ExoPlayer exige d'être piloté depuis son thread d'application.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Activité au premier plan, pour relayer les flèches/OK/retour de la télécommande
    // (nav_*) sous forme de vrais KeyEvent D-pad — générique à tout écran (accueil,
    // listes, menus du lecteur…) puisque toute l'app est déjà navigable au D-pad
    // (Leanback/TV). Pas de logique de navigation dupliquée par écran.
    @Volatile private var currentActivity: Activity? = null

    // Public : les sauvegardes critiques déclenchées à la sortie d'une activité
    // (position/« vu » de fin de lecture) doivent survivre au finish() de l'activité
    // et à la destruction de son ViewModel — sinon la coroutine est annulée avant
    // d'avoir écrit (ex. épisode non marqué « vu » lors de l'enchaînement auto).
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this) }
    val sessionManager by lazy { SessionManager(this) }
    val movieCastCache by lazy { MovieCastCache(this) }
    val finishedMoviesCache by lazy { FinishedMoviesCache(this) }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            // Étiquette d'appareil pour admin.nicotv.ovh (« qui regarde quoi, sur quoi »)
            // — lue côté serveur dans record_presence()/presence_device_from_request().
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .addHeader("X-Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    .addHeader("X-Device-Id", sessionManager.getOrCreateDeviceId())
                    .build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    })
                }
            }
            .build()
    }

    // Injecte api_key/language sur chaque requête TMDb : évite de les répéter
    // (et de les faire fuiter dans les logs d'appel) sur chaque endpoint de TmdbApi.
    private val tmdbOkHttpClient by lazy {
        okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url.newBuilder()
                    .addQueryParameter("api_key", AppConfig.Tmdb.API_KEY)
                    .addQueryParameter("language", AppConfig.Tmdb.LANGUAGE)
                    .build()
                chain.proceed(original.newBuilder().url(url).build())
            }
            .build()
    }

    val tmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.Tmdb.BASE_URL)
            .client(tmdbOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    val authApi by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.Auth.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    val nicoTvApi by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.NicoTv.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NicoTvApi::class.java)
    }

    val catalogApi by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.Catalog.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CatalogApi::class.java)
    }

    val mediaRepository by lazy {
        MediaRepository(
            database.movieDao(),
            database.seriesDao(),
            database.episodeDao(),
            database.favoriteDao(),
            database.seriesFavoriteDao(),
            database.watchHistoryDao(),
            database.seenEpisodeDao(),
            database.seenMovieDao(),
            database.seenSeriesDao(),
            database.newDetectEpisodeDao(),
            tmdbApi,
            catalogApi,
            nicoTvApi,
            finishedMoviesCache
        )
    }

    val downloadRepository by lazy { DownloadRepository(this, database.downloadDao()) }

    // État réseau (mode avion / pas de wifi-data) — utilisé pour ne montrer que les
    // téléchargements locaux dans Films/Séries quand l'appareil est hors-ligne.
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val _isOnline = MutableLiveData(true)
    val isOnline: LiveData<Boolean> get() = _isOnline

    private fun hasInternetNow(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerConnectivityCallback() {
        _isOnline.value = hasInternetNow()
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                _isOnline.postValue(hasInternetNow())
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _isOnline.postValue(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
        })
    }

    private val wsClient by lazy {
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    }
    private val realtimeClient by lazy {
        RealtimeClient(wsClient, sessionManager, appScope, onRemoteCommand = { data -> handleRemoteCommand(data) }) { onRealtimeCatalogChange() }
    }
    @Volatile private var catalogSyncJob: Job? = null

    fun connectRealtime() {
        if (sessionManager.isLoggedIn()) realtimeClient.start()
        else android.util.Log.w("IptvApplication", "connectRealtime() ignoré : pas connecté")
    }

    fun disconnectRealtime() {
        realtimeClient.stop()
    }

    // Présence « en ligne » hors lecture (accueil, listes, fiche détail…) — admin.nicotv.ovh
    // doit voir l'appareil dès que l'app est au premier plan, pas seulement pendant un film.
    // Le lecteur a déjà son propre heartbeat détaillé (PlayerViewModel.sendHeartbeat) → ce
    // ticker se tait tant que PlayerActivity.current n'est pas null, même logique que côté
    // PWA (sendAppHeartbeat() ignoré tant que .player existe dans le DOM).
    @Volatile private var appHbJob: Job? = null
    fun startAppHeartbeat() {
        if (appHbJob != null) return
        appHbJob = appScope.launch {
            while (isActive) {
                if (sessionManager.isLoggedIn() && com.nicotv.iptv.player.PlayerActivity.current == null) {
                    runCatching { mediaRepository.sendAppHeartbeat(PresenceScreen.label, sessionManager.bearer()) }
                }
                delay(20_000)
            }
        }
    }
    fun stopAppHeartbeat() {
        appHbJob?.cancel(); appHbJob = null
    }

    /** Change l'écran courant + le remonte tout de suite côté admin (pas d'attente du
     *  prochain tick 20s) — appelé depuis onResume() de chaque activité. */
    fun reportScreen(label: String) {
        PresenceScreen.label = label
        if (!sessionManager.isLoggedIn() || com.nicotv.iptv.player.PlayerActivity.current != null) return
        appScope.launch { runCatching { mediaRepository.sendAppHeartbeat(label, sessionManager.bearer()) } }
    }

    // Contrôle à distance depuis admin.nicotv.ovh (bus WS, topic user:<uid>, event
    // "remote") : ne fait quelque chose que si l'app est déjà au premier plan (WS
    // coupé en arrière-plan, cf. onStop ci-dessous) — pas de réveil d'app fermée.
    private fun handleRemoteCommand(data: org.json.JSONObject) {
        // Plusieurs sessions du même compte peuvent être ouvertes sur des appareils
        // différents (topic WS user:<uid> partagé) → ne réagit que si la commande
        // cible explicitement CET appareil.
        if (data.optString("deviceId") != sessionManager.getOrCreateDeviceId()) return
        // onMessage() de RealtimeClient (OkHttp) tourne sur un thread de fond, pas le
        // thread UI — ExoPlayer exige d'être piloté depuis son thread d'application
        // (celui où le Player a été créé, ici le thread principal) : pause()/play()
        // appelés d'ici échouaient silencieusement, sans exception visible (incident
        // 2026-08-01 : "lancer un film" marchait car startActivity() tolère n'importe
        // quel thread, contrairement à un appel direct sur le Player).
        when (data.optString("cmd")) {
            "pause" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remotePause() }
            "resume" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remoteResume() }
            "seek" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remoteSeek((data.optDouble("value", 0.0) * 1000).toLong()) }
            "volume" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remoteSetVolume(data.optDouble("value", 1.0).toFloat()) }
            "mute" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remoteSetMute(true) }
            "unmute" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remoteSetMute(false) }
            // Sélection DIRECTE par index (menu construit côté télécommande via
            // get_tracks/remoteReportTracks) — plus l'ouverture aveugle du menu local
            // (remoteOpenAudioMenu/remoteOpenSubtitleMenu, gardées mais plus appelées
            // par ces deux cmd), cf. incident 2026-08-04 "j'aimerais avoir un petit menu".
            "audio" -> mainHandler.post {
                val idx = data.optInt("value", -1)
                if (idx >= 0) com.nicotv.iptv.player.PlayerActivity.current?.remoteSetAudioTrack(idx)
            }
            "subtitle" -> mainHandler.post {
                val idx: Int? = if (data.isNull("value")) null else when (val raw = data.opt("value")) {
                    is Number -> raw.toInt()
                    is String -> if (raw == "none") null else raw.toIntOrNull()
                    else -> null
                }
                com.nicotv.iptv.player.PlayerActivity.current?.remoteSetSubtitleTrack(idx)
            }
            "get_tracks" -> mainHandler.post { com.nicotv.iptv.player.PlayerActivity.current?.remoteReportTracks() }
            "text_set" -> mainHandler.post { setRemoteText(data.optString("value")) }
            "nav_up" -> mainHandler.post { dispatchNavKey(android.view.KeyEvent.KEYCODE_DPAD_UP) }
            "nav_down" -> mainHandler.post { dispatchNavKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
            "nav_left" -> mainHandler.post { dispatchNavKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
            "nav_right" -> mainHandler.post { dispatchNavKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
            "nav_select" -> mainHandler.post { dispatchNavKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER) }
            "nav_back" -> mainHandler.post {
                val player = com.nicotv.iptv.player.PlayerActivity.current
                if (player != null) player.remoteBack() else dispatchNavKey(android.view.KeyEvent.KEYCODE_BACK)
            }
            "mouse_move" -> mainHandler.post { handleMouseMove(data.optString("value")) }
            "mouse_click" -> mainHandler.post { handleMouseClick() }
            "play" -> {
                val tmdbId = data.optInt("tmdbId", -1)
                if (tmdbId <= 0) return
                appScope.launch {
                    val username = sessionManager.getUsername()
                    if (username.isBlank()) return@launch
                    val movie = runCatching { mediaRepository.findOwnedMovie(username, tmdbId) }.getOrNull() ?: return@launch
                    val intent = android.content.Intent(this@IptvApplication, com.nicotv.iptv.player.PlayerActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(com.nicotv.iptv.player.PlayerActivity.EXTRA_MOVIE_ID, movie.id)
                        putExtra(com.nicotv.iptv.player.PlayerActivity.EXTRA_STREAM_URL, movie.streamUrl)
                        putExtra(com.nicotv.iptv.player.PlayerActivity.EXTRA_TITLE, movie.title)
                        putExtra(com.nicotv.iptv.player.PlayerActivity.EXTRA_RESUME, true)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    /** Relaie une flèche/OK/retour de la télécommande vers l'activité au premier plan
     *  sous forme de vrai KeyEvent D-pad — doit être appelé depuis le thread UI (déjà
     *  posté sur mainHandler par handleRemoteCommand). */
    /** Saisie de texte à distance (onglet « Clavier » de RemoteControlActivity, parité
     *  PWA setRemoteText()) : remplace le contenu du champ actuellement focus sur cet
     *  appareil — pratique pour la recherche/le login sans taper au D-pad. */
    private fun setRemoteText(text: String) {
        val focused = currentActivity?.currentFocus as? android.widget.EditText ?: return
        focused.setText(text)
        focused.setSelection(text.length)
    }

    private fun findFirstFocusable(v: android.view.View): android.view.View? {
        if (v.visibility == android.view.View.VISIBLE && v.isFocusable) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                findFirstFocusable(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun dispatchNavKey(keyCode: Int) {
        val decorView = currentActivity?.window?.decorView ?: return
        val direction = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
            else -> null
        }
        if (direction != null) {
            // focusSearch() APPELÉ DIRECTEMENT (pas un KeyEvent dispatché en espérant
            // que le système le fasse lui-même) : OK/Retour marchaient déjà (routes
            // différentes) mais les 4 flèches restaient mortes malgré un focus initial
            // présent — signe que le KeyEvent synthétique se faisait avaler en route
            // (un onKeyDown custom quelque part, ou une subtilité de dispatch) avant
            // d'atteindre le focus-search par défaut (incident 2026-08-04, confirmé :
            // OK/Retour OK, flèches mortes). focusSearch() est l'API que le système
            // utilise en interne — on la déclenche nous-mêmes, sans dépendre du trajet
            // de l'évènement.
            val current = decorView.findFocus()
            val next = if (current != null) current.focusSearch(direction) else findFirstFocusable(decorView)
            next?.requestFocus()
            return
        }
        if (decorView.findFocus() == null) {
            (findFirstFocusable(decorView) ?: decorView).requestFocus()
        }
        decorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        decorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    // ── Curseur souris virtuel (pavé tactile de RemoteControlActivity, parité PWA) ──
    // Position RELATIVE (deltas cumulés, pas absolue) : le pavé source n'a pas les
    // mêmes dimensions que l'écran cible. Clic = vrai MotionEvent DOWN/UP injecté au
    // decorView (comme dispatchNavKey pour les KeyEvent) — fonctionne sur n'importe
    // quel écran sans permission spéciale (contrairement à un AccessibilityService).
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorInit = false
    private var cursorView: android.view.View? = null

    private fun ensureCursorView(activity: Activity): android.view.View {
        cursorView?.let { if (it.isAttachedToWindow) return it }
        val dp = activity.resources.displayMetrics.density
        val size = (18 * dp).toInt()
        val v = android.view.View(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(size, size)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#00D4FF"))
                setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
            }
            elevation = 999f
            visibility = android.view.View.GONE
        }
        (activity.window.decorView as? android.view.ViewGroup)?.addView(v)
        cursorView = v
        return v
    }

    private fun handleMouseMove(value: String?) {
        val activity = currentActivity ?: return
        val parts = (value ?: "0,0").split(",")
        val dx = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
        val dy = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        val dm = activity.resources.displayMetrics
        if (!cursorInit) { cursorX = dm.widthPixels / 2f; cursorY = dm.heightPixels / 2f; cursorInit = true }
        cursorX = (cursorX + dx).coerceIn(0f, dm.widthPixels - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, dm.heightPixels - 1f)
        val v = ensureCursorView(activity)
        v.x = cursorX - v.layoutParams.width / 2f
        v.y = cursorY - v.layoutParams.height / 2f
        v.visibility = android.view.View.VISIBLE
        // Survol = focus (comme le curseur PWA) : donne un retour visuel (anneau/
        // surbrillance déjà géré par le système de focus D-pad existant) pendant le
        // déplacement, pas seulement au clic.
        activity.window?.decorView?.let { decor ->
            findViewAtPoint(decor, cursorX.toInt(), cursorY.toInt())?.let { under ->
                if (under.isFocusable && !under.isFocused) under.requestFocus()
            }
        }
    }

    /** Vue CLIQUABLE la plus profonde/au-dessus contenant ce point écran — DFS des
     *  enfants du dernier au premier (le dernier ajouté est dessiné au-dessus).
     *  Exclut le curseur lui-même (posé en tout dernier sur le decorView). */
    private fun findViewAtPoint(view: android.view.View, x: Int, y: Int): android.view.View? {
        if (view === cursorView || view.visibility != android.view.View.VISIBLE) return null
        val rect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return null
        if (view is android.view.ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                findViewAtPoint(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return if (view.isClickable) view else null
    }

    private fun handleMouseClick() {
        // Un tap SANS glissé préalable (aucun mouse_move jamais reçu) ne devait plus
        // être un no-op silencieux : le curseur se centrait à l'écran (comme
        // handleMouseMove) — sinon le tout premier clic ne faisait jamais rien
        // (incident 2026-08-04, target APK).
        val activity = currentActivity ?: return
        if (!cursorInit) {
            val dm = activity.resources.displayMetrics
            cursorX = dm.widthPixels / 2f; cursorY = dm.heightPixels / 2f; cursorInit = true
        }
        val decorView = activity.window?.decorView ?: return
        // performClick() DIRECT sur la vue trouvée sous le curseur — même principe
        // que le fix des flèches D-pad (focusSearch() direct) : un MotionEvent
        // synthétique dispatché via dispatchTouchEvent() se faisait avaler en route
        // sur la fiche film et le lecteur sans jamais déclencher le clic visé
        // (incident 2026-08-04). performClick() ne dépend d'aucune propagation
        // d'évènement, juste de trouver la bonne vue.
        val target = findViewAtPoint(decorView, cursorX.toInt(), cursorY.toInt())
        if (target != null) {
            target.performClick()
            return
        }
        val t = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        decorView.dispatchTouchEvent(down)
        decorView.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    private fun onRealtimeCatalogChange() {
        if (!sessionManager.isLoggedIn()) return
        val user = sessionManager.getUsername()
        if (user.isBlank()) return
        catalogSyncJob?.cancel()
        catalogSyncJob = appScope.launch {
            delay(1_500L)
            val result = runCatching { mediaRepository.syncCatalog(user, sessionManager.bearer()) }
            android.util.Log.d("IptvApplication", "Sync temps réel : $result")
        }
    }

    // Aucun crash reporting jusqu'ici (ni Crashlytics ni handler perso) → un plantage
    // Java ne laissait AUCUNE trace exploitable après coup (logcat vidé au redémarrage
    // de l'appareil). Fichier local (survit à un reboot) : préfixe le handler système
    // par défaut, ne le remplace pas (le crash/kill du process reste normal).
    // Écrit dans le stockage externe spécifique à l'app (getExternalFilesDir, PAS
    // filesDir) : sur un build release non-debuggable, `adb shell run-as` est
    // refusé ("package not debuggable") donc filesDir est inaccessible depuis un
    // PC sans root sur l'appareil — le shell adb garde un accès legacy à
    // /sdcard/Android/data/<pkg>/files/ (groupe sdcard_rw) même sans run-as, donc
    // `adb pull` y fonctionne toujours (constaté 2026-08-19, Shield en release).
    // Repli sur filesDir si le stockage externe est indisponible (carte SD éjectée
    // — cas quasi inexistant sur Shield, stockage interne toujours monté).
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val logFile = java.io.File(getExternalFilesDir(null) ?: filesDir, "crash_log.txt")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.FRANCE)
                    .format(java.util.Date())
                val entry = "\n=== $date — thread ${thread.name} ===\n$sw"
                val existing = if (logFile.exists()) logFile.readText() else ""
                logFile.writeText((existing + entry).takeLast(200_000))
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .crossfade(true)
                .allowHardware(false)
                .okHttpClient(okHttpClient)
                .diskCache(
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.1)
                        .build()
                )
                .build()
        )

        // Relance le suivi d'éventuels téléchargements en cours (process tué pendant un download).
        downloadRepository.resumePendingDownloads()
        registerConnectivityCallback()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (currentActivity === activity) currentActivity = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                connectRealtime()
                startAppHeartbeat()
            }
            override fun onStop(owner: LifecycleOwner) {
                disconnectRealtime()
                stopAppHeartbeat()
            }
        })
    }
}
