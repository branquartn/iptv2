package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_episodes")
data class SeenEpisodeEntity(
    @PrimaryKey val fileKey: String,
    val watchedAt: Long = System.currentTimeMillis()
)
