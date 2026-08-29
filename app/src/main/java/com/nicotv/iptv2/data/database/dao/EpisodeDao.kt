package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonNumber ASC, episodeNumber ASC, episodeTitle ASC")
    suspend fun getEpisodesForSeries(seriesId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes")
    fun getAllEpisodesFlow(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes")
    suspend fun getAllEpisodes(): List<EpisodeEntity>

    /** Épisodes désignés par la reprise de lecture — cf.
     * PlaylistRepository.getUnifiedHistory. ⚠️ Liste à découper par
     * l'appelant (SQLITE_MAX_VARIABLES). */
    @Query("SELECT * FROM episodes WHERE watchKey IN (:keys)")
    suspend fun getEpisodesByWatchKeys(keys: List<Long>): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE watchKey = :watchKey LIMIT 1")
    suspend fun getEpisodeByWatchKey(watchKey: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun deleteForSeries(seriesId: Long)
}
