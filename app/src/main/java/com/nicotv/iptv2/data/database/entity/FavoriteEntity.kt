package com.nicotv.iptv2.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val movieId: Long,
    val addedAt: Long = System.currentTimeMillis()
)
