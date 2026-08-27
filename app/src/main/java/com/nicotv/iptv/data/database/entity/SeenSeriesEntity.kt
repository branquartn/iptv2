package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Série « ouverte au moins une fois » (détection NOUVEAU, comme snames côté
 * PWA getSeenLib()) — PAS le suivi épisode « vu jusqu'au bout » (SeenEpisodeEntity/
 * canal epseen), qui est un concept différent. */
@Entity(tableName = "seen_series")
data class SeenSeriesEntity(
    @PrimaryKey val name: String,
    val seenAt: Long = System.currentTimeMillis()
)
