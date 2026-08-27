package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "series_favorites")
data class SeriesFavoriteEntity(
    @PrimaryKey val seriesId: Long,
    val addedAt: Long = System.currentTimeMillis()
)
