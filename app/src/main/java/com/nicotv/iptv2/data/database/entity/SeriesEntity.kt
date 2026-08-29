package com.nicotv.iptv2.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.util.leadingLanguageCodeOrEmpty
import com.nicotv.iptv2.util.withoutLeadingLanguageCode

/** Une série. Index unique sur title : un rechargement de la playlist met à
 * jour la ligne existante au lieu d'en créer une doublonnée. */
@Entity(
    tableName = "series",
    // Cf. MovieEntity pour le raisonnement (audit perf 30/08/2026).
    indices = [
        Index(value = ["title"], unique = true),
        Index(value = ["category", "sortOrder", "categoryOrder"]),
        Index(value = ["sortOrder"]),
        Index(value = ["languageCode"]),
        Index(value = ["updatedAt"])
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseYear: String = "",
    val rating: Float = 0f,
    val genres: String = "",
    val category: String = "",
    // Identifiant côté Xtream Codes (series_id) — vide pour une série détectée
    // depuis un M3U (regroupement par titre, cf. M3uParser). Sert à retrouver les
    // épisodes via get_series_info lors de l'ouverture de la fiche.
    val xtreamSeriesId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    // ⚠️ Ajoutés 29/08/2026 (pagination écran Séries) — cf. MovieEntity, mêmes
    // colonnes, même rôle et même invariant (code vide ⇒ categoryStripped ==
    // category). Calculés une fois au chargement de la playlist, jamais au
    // runtime : permettent de filtrer langue/catégorie en SQL (SeriesDao.
    // getSeriesPage) sans mapper tout le catalogue en mémoire.
    @ColumnInfo(defaultValue = "") val languageCode: String = "",
    @ColumnInfo(defaultValue = "") val categoryStripped: String = "",
    /** Cf. MovieEntity.categoryOrder — ordre de la catégorie dans la source. */
    @ColumnInfo(defaultValue = "0") val categoryOrder: Int = 0,
    /** Cf. MovieEntity.sortOrder — rang de la série dans la source. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
) {
    fun toDomain(isFavorite: Boolean = false) = Movie(
        id = id,
        title = title,
        streamUrl = "",
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = overview,
        releaseYear = releaseYear,
        rating = rating,
        genres = if (genres.isBlank()) emptyList() else genres.split(","),
        category = category,
        isFavorite = isFavorite,
        type = Movie.Type.SERIES
    )

    companion object {
        /** Cf. MovieEntity.languageCodeFor — mêmes helpers partagés. */
        fun languageCodeFor(category: String): String = leadingLanguageCodeOrEmpty(category)

        fun categoryStrippedFor(category: String): String = withoutLeadingLanguageCode(category)
    }
}
