package com.nicotv.iptv.domain.model

/** Film/série TMDb (recommandations, filmographie acteur) — badge ✓ si déjà dans
 * la médiathèque (le clic ouvre sa fiche) ou + sinon (le clic l'ajoute à la file
 * de téléchargement, même flux que l'écran Recherche). Partagé entre la fiche
 * détail (casting/réalisateur/similaires) et l'écran Recherche (acteurs). */
data class SimilarWork(
    val tmdbId: Int,
    val isTv: Boolean,
    val title: String,
    val year: String,
    val posterUrl: String,
    val owned: Boolean,
    // Pour l'aperçu (synopsis + bande-annonce) au clic sur la carte — déjà en main
    // via /recommendations ou /combined_credits, pas de fetch supplémentaire.
    val overview: String = "",
    val backdropUrl: String = "",
    val rating: Float = 0f
)

/** Cible de navigation quand on tape un film/série déjà possédé (filmographie
 * acteur, films similaires, recherche). */
sealed class OpenTarget {
    data class MovieTarget(val movieId: Long) : OpenTarget()
    data class SeriesTarget(val seriesId: Long, val title: String) : OpenTarget()
}
