package com.nicotv.iptv2.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nicotv.iptv2.domain.model.Movie

/** Un film VOD. Index unique (title, streamUrl) : un rechargement de la playlist
 * met à jour la ligne existante au lieu d'en créer une doublonnée. */
@Entity(
    tableName = "movies",
    indices = [Index(value = ["title", "streamUrl"], unique = true)]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val streamUrl: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseYear: String = "",
    val runtime: Int = 0,
    val rating: Float = 0f,
    val genres: String = "",
    val category: String = "",
    // Résolu au chargement de la playlist (recherche TMDb par titre, cf.
    // PlaylistRepository.enrichMovies) — 0 si aucune correspondance trouvée.
    // Réutilisé par la fiche détail pour éviter de re-chercher à l'ouverture.
    // @ColumnInfo(defaultValue) OBLIGATOIRE ici : sans lui, le schéma que Room
    // attend (colonne NOT NULL sans DEFAULT) ne correspond plus à celui produit
    // par MIGRATION_2_3 (ALTER TABLE ... DEFAULT 0 — SQLite l'exige pour ajouter
    // une colonne NOT NULL à une table non vide) → Room refuse la migration et
    // l'app crash au démarrage (IllegalStateException: Migration didn't
    // properly handle...), même symptôme que "rien ne s'affiche".
    @ColumnInfo(defaultValue = "0")
    val tmdbId: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(
        isFavorite: Boolean = false,
        watchProgress: Int = 0,
        isFinished: Boolean = false
    ) = Movie(
        id = id,
        title = title,
        streamUrl = streamUrl,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = overview,
        releaseYear = releaseYear,
        runtime = runtime,
        rating = rating,
        genres = if (genres.isBlank()) emptyList() else genres.split(","),
        category = category,
        tmdbId = tmdbId,
        isFavorite = isFavorite,
        watchProgress = watchProgress,
        isFinished = isFinished
    )
}
