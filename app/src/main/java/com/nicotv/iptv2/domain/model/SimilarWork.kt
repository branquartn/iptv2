package com.nicotv.iptv2.domain.model

/** Film/série recommandé par TMDb (films similaires, filmographie acteur).
 * [owned] : trouvé dans le catalogue chargé (par titre — nos films/séries ne
 * portent pas de tmdbId, contrairement à NicoTV) → le clic ouvre sa fiche.
 * Sinon, rien à ouvrir (pas de backend pour l'ajouter à la playlist). */
data class SimilarWork(
    val tmdbId: Int,
    val isTv: Boolean,
    val title: String,
    val year: String,
    val posterUrl: String,
    val owned: Boolean,
    val overview: String = "",
    val backdropUrl: String = "",
    val rating: Float = 0f
)

/** Cible de navigation quand on tape un film/série déjà présent dans le
 * catalogue (filmographie acteur, films similaires). */
sealed class OpenTarget {
    data class MovieTarget(val movieId: Long) : OpenTarget()
    data class SeriesTarget(val seriesId: Long, val title: String, val posterUrl: String) : OpenTarget()
}
