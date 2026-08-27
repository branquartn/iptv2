package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nicotv.iptv.domain.model.Movie

/**
 * Une série. Index unique (username, title) :
 * une nouvelle synchro remplace la ligne au lieu d'en créer un doublon.
 */
@Entity(
    tableName = "series",
    indices = [Index(value = ["username", "title"], unique = true)]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String = "",
    val title: String,
    val streamUrl: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseYear: String = "",
    val runtime: Int = 0,
    val rating: Float = 0f,
    val genres: String = "",
    val tmdbId: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = Movie(
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
        tmdbId = tmdbId,
        isFavorite = false
    )
}
