package com.nicotv.iptv.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Téléchargement local (mode avion). `key` = "movie:<id>" pour un film,
 * ou le `fileKey` ("Série/Fichier.mkv") pour un épisode — pas de clé composite.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val key: String,
    val type: String,              // "movie" | "episode"
    val seriesId: Long = -1L,
    val title: String,
    val episodeTitle: String = "",
    val seasonNumber: Int = -1,
    val episodeNumber: Int = -1,
    val posterUrl: String = "",
    val sourceUrl: String,
    val localPath: String = "",
    val state: String,             // QUEUED | DOWNLOADING | COMPLETED | FAILED
    val osDownloadId: Long = -1L,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_MOVIE = "movie"
        const val TYPE_EPISODE = "episode"
        const val STATE_QUEUED = "QUEUED"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"

        fun movieKey(movieId: Long) = "movie:$movieId"
    }
}
