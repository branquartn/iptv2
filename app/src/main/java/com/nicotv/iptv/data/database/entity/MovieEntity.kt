package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nicotv.iptv.domain.model.Movie

@Entity(tableName = "movies")
data class MovieEntity(
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
    // Premier ajout du titre au catalogue (sert au badge « NOUVEAU »). Contrairement à
    // updatedAt, n'est PAS modifié quand l'URL de flux change lors d'une resynchro.
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(
        isFavorite: Boolean = false,
        isNew: Boolean = false,
        watchProgress: Int = 0,
        isSeen: Boolean = false,
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
        tmdbId = tmdbId,
        isFavorite = isFavorite,
        isNew = isNew,
        watchProgress = watchProgress,
        isSeen = isSeen,
        isFinished = isFinished
    )
}
