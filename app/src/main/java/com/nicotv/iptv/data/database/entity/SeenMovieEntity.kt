package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_movies")
data class SeenMovieEntity(
    @PrimaryKey val historyKey: String, // "m{tmdbId}" ou "movie:{id}"
    val watchedAt: Long = System.currentTimeMillis()
)
