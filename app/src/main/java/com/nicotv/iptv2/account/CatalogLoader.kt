package com.nicotv.iptv2.account

import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.ui.main.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Chargement de la playlist EN ARRIÈRE-PLAN : survit au changement d'écran, expose un pourcentage
 * que chaque écran affiche en bandeau en haut (cf. BaseActivity) — on peut continuer à naviguer et,
 * côté Xtream, regarder les chaînes dès qu'elles sont prêtes (elles sont enregistrées en premier).
 */
class CatalogLoader(private val app: IptvApplication) {

    data class State(val percent: Int, val message: String, val profileName: String, val error: String? = null)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Le catalogue de ce profil doit-il être (re)chargé ? Jamais chargé, plus vieux que 24 h, ou construit avec d'autres
     *  réglages films/séries que ceux de l'admin. Sinon le cache de sa base est servi tel quel (bascule instantanée). */
    fun needsLoad(profileId: Long): Boolean {
        val loadedAt = app.sourcePrefs.getLoadedAt(profileId)
        if (loadedAt <= 0L || System.currentTimeMillis() - loadedAt > com.nicotv.iptv2.data.repository.PlaylistRepository.CATALOG_MAX_AGE_MS) return true
        val loadedFlags = app.sessionManager.getLoadedFlags(profileId) ?: (true to true)
        return loadedFlags != app.sessionManager.getContentFlags(profileId)
    }

    /** Active une playlist : si son cache est récent on la sert tout de suite, sinon chargement en arrière-plan. */
    fun activate(profileId: Long, profileName: String): Boolean {
        if (isRunning) return false
        app.sourcePrefs.setActiveProfileId(profileId)
        if (needsLoad(profileId)) start(profileId, profileName)
        return true
    }

    /** Au démarrage : recharge la playlist active seulement si son cache est périmé. */
    fun refreshIfNeeded() {
        if (isRunning) return
        app.appScope.launch {
            val p = app.playlistRepository.getActiveProfile() ?: return@launch
            if (needsLoad(p.id)) start(p.id, p.name)
        }
    }

    fun start(profileId: Long, profileName: String) {
        if (isRunning) return
        job = app.appScope.launch {
            _state.value = State(0, "Démarrage…", profileName)
            // Profil actif dès maintenant : si l'appli est relancée en cours de chargement, on retombe dessus.
            app.sourcePrefs.setActiveProfileId(profileId)
            val result = app.playlistRepository.loadProfile(profileId) { pct, msg ->
                _state.value = State(pct, msg, profileName)
            }
            result.onSuccess {
                MainActivity.resetHomeBg()   // fond d'accueil : pas de jaquette de l'ancienne source
                _state.value = State(100, "Playlist prête", profileName)
                delay(2_000)
                _state.value = null
            }.onFailure { e ->
                _state.value = State(0, "", profileName, error = e.message ?: "Erreur de chargement")
                delay(6_000)
                _state.value = null
            }
        }
    }
}
