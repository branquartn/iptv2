package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = SeriesEntity::class,
        parentColumns = ["id"],
        childColumns = ["seriesId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("seriesId"), Index("watchKey")]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val seasonNumber: Int,
    val seasonName: String,
    val episodeNumber: Int,
    val episodeTitle: String,
    val overview: String = "",
    val streamUrl: String,
    val fileKey: String = "", // Clé unique stable: "Nom Série/Fichier.mkv"
    val watchKey: Long = 0    // Clé d'historique (1G + hash fileKey)
) {
    companion object {
        const val WATCH_OFFSET = 1_000_000_000L
        
        fun computeWatchKey(fileKey: String): Long {
            return WATCH_OFFSET + (fileKey.hashCode().toLong() and 0xFFFFFFFFL)
        }
    }
}
