package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Épisode connu au moment où la série a été ouverte (canal seen.episodes côté
 * PWA — détection NOUVEAU uniquement). DISTINCT de SeenEpisodeEntity/epseen
 * (épisode regardé jusqu'au bout) : une série avec de nouveaux épisodes non
 * encore vus dans cette liste redevient NOUVEAU, même si son nom est déjà
 * connu (cf. getSeries() côté MediaRepository, comme isNewItem() côté PWA). */
@Entity(tableName = "seen_episodes_new")
data class NewDetectEpisodeEntity(
    @PrimaryKey val fileKey: String,
    val seenAt: Long = System.currentTimeMillis()
)
