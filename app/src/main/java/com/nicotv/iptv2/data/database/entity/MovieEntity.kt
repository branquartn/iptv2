package com.nicotv.iptv2.data.database.entity

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
        isFavorite = isFavorite,
        watchProgress = watchProgress,
        isFinished = isFinished
    )
}
