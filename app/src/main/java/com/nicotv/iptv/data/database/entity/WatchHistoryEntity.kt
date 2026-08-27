package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val historyKey: String, // "m123" ou "e:Serie/Ep.mkv"
    val movieId: Long,                  // ID local (film.id ou episode.watchKey)
    val title: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watchedAt: Long = System.currentTimeMillis()
) {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt() else 0
}
