package com.nicotv.iptv2.domain.model

data class Movie(
    val id: Long,
    val title: String,
    val streamUrl: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseYear: String = "",
    val runtime: Int = 0,
    val rating: Float = 0f,
    val genres: List<String> = emptyList(),
    val category: String = "",
    // Résolu au chargement de la playlist (recherche TMDb par titre) — 0 si
    // aucune correspondance. Réutilisé par la fiche détail (casting/similaires).
    val tmdbId: Int = 0,
    val isFavorite: Boolean = false,
    // Progression de lecture en cours (0 = jamais lu, 100 = terminé).
    val watchProgress: Int = 0,
    // Film/épisode regardé jusqu'au bout → badge « ✓ Vu » sur l'affiche.
    val isFinished: Boolean = false,
    // Type d'élément (film ou épisode de série).
    val type: Type = Type.MOVIE,
    // Pour les épisodes, l'URL de flux est requise pour la lecture directe depuis l'historique.
    val episodeKey: String = "",
    // Pour les épisodes uniquement : requis pour que PlayerActivity sache qu'il
    // s'agit d'une série (prompt/enchaînement épisode suivant) quand la lecture
    // démarre depuis l'historique (ResumeActivity) plutôt que la fiche série.
    val seriesId: Long = -1L,
    val seriesTitle: String = ""
) {
    enum class Type { MOVIE, EPISODE, SERIES }
    val genresFormatted: String get() = genres.joinToString(" • ")
    val ratingFormatted: String get() = if (rating > 0) "★ ${"%.1f".format(rating)}" else ""
    val runtimeFormatted: String get() = if (runtime > 0) "${runtime / 60}h ${runtime % 60}min" else ""

    /** Film commencé mais non terminé → barre de reprise affichée sur l'affiche. */
    val inProgress: Boolean get() = watchProgress > 0
}
