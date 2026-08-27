package com.nicotv.iptv.util

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Marque les films regardés JUSQU'AU BOUT (badge « ✓ Vu » sur le mur), distinct
 * de seenMovieDao (qui marque « ouvert au moins une fois », pour le badge
 * NOUVEAU). Synchronisé entre appareils via le canal serveur « mfinished »
 * (push en fin de lecture, pull dans syncRemoteState).
 *
 * Clés = les mêmes clés STABLES que seen_movies ("m<tmdbId>", repli
 * "movie:<id Room>") — PAS l'id Room seul : les ids autoincrement ne survivent
 * pas à une migration destructive ni à un ré-ajout après suppression.
 *
 * Exposé en StateFlow (pas juste SharedPreferences) : les listes de films
 * (getMoviesWithFavorites) sont des Flow combinés — une écriture
 * SharedPreferences seule ne re-déclenche AUCUNE émission, donc un badge reçu
 * par sync n'apparaîtrait qu'au prochain changement fortuit d'une autre
 * source. Le StateFlow rend l'écriture observable ; les prefs restent la
 * persistance entre démarrages. */
class FinishedMoviesCache(context: Context) {
    private val prefs = context.getSharedPreferences("nicotv_finished_movies", Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow<Set<String>>(prefs.all.keys)
    val keysFlow: StateFlow<Set<String>> = _keys

    fun markFinished(key: String) {
        prefs.edit { putBoolean(key, true) }
        _keys.value = _keys.value + key
    }

    /** Aligne le cache sur l'ensemble serveur (canal mfinished) : ajoute les
     * nouveaux ET retire ceux qui n'y sont plus. Nécessaire pour propager une
     * remise à zéro faite ailleurs (kodi/PWA) — sinon le badge « ✓ Vu » restait
     * affiché ici alors que le film n'est plus marqué fini côté serveur. */
    fun replaceAll(keys: Set<String>) {
        prefs.edit { clear(); keys.forEach { putBoolean(it, true) } }
        _keys.value = keys
    }

    fun allKeys(): Set<String> = _keys.value
}
