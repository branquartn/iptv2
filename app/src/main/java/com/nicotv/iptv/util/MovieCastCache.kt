package com.nicotv.iptv.util

import android.content.Context
import androidx.core.content.edit

/** Cache persistant (comme la PWA — `nicotv_castnames` en localStorage) des noms
 * d'acteurs ET réalisateurs par film, pour que la recherche locale trouve aussi
 * les films où joue/qu'a réalisé une personne donnée, pas seulement une
 * correspondance de titre. Une entrée par film (clé = id local Room), valeur =
 * noms en minuscule séparés par "|". Préchauffé en tâche de fond, débit limité
 * (cf. MoviesViewModel.prefetchCast), pas à chaque frappe de recherche.
 * Fichier de préférences bumpé _v2 (réalisateurs ajoutés) : force un re-fetch,
 * sinon les films déjà en cache (acteurs seuls) resteraient sans réalisateur
 * indéfiniment (isKnown() ne ré-interroge jamais un film déjà connu). */
class MovieCastCache(context: Context) {
    private val prefs = context.getSharedPreferences("nicotv_castnames_v2", Context.MODE_PRIVATE)

    fun isKnown(movieId: Long): Boolean = prefs.contains(movieId.toString())

    fun put(movieId: Long, names: List<String>) {
        prefs.edit { putString(movieId.toString(), names.joinToString("|") { it.lowercase() }) }
    }

    /** Vrai si le casting connu de ce film contient la requête (sous-chaîne, comme
     * la recherche par titre existante). Renvoie false si le film n'est pas encore
     * en cache (pas de faux positif tant que le préchauffage n'est pas passé). */
    fun matches(movieId: Long, query: String): Boolean {
        val cached = prefs.getString(movieId.toString(), null) ?: return false
        return cached.foldAccents().contains(query.lowercase().foldAccents())
    }
}
