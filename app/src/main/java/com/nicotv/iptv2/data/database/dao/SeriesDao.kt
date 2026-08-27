package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE username = :username ORDER BY title ASC")
    fun getAllSeries(username: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE title LIKE '%' || :query || '%' AND username = :username ORDER BY title ASC")
    suspend fun searchSeries(query: String, username: String): List<SeriesEntity>

    @Query("SELECT id FROM series WHERE username = :username AND title = :title LIMIT 1")
    suspend fun findId(username: String, title: String): Long?

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE username = :username AND tmdbId = :tmdbId LIMIT 1")
    suspend fun getSeriesByTmdbId(username: String, tmdbId: Int): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(series: SeriesEntity): Long

    @Update
    suspend fun update(series: SeriesEntity)

    @Query("SELECT COUNT(*) FROM series WHERE username = :username")
    suspend fun countForUser(username: String): Int

    @Query("DELETE FROM series WHERE title NOT IN (:activeTitles) AND username = :username")
    suspend fun deleteObsoleteSeries(activeTitles: List<String>, username: String)

    @Query("DELETE FROM series WHERE username = :username")
    suspend fun deleteForUser(username: String)

    @Query("SELECT * FROM series WHERE username = :username ORDER BY title ASC")
    suspend fun getAllSeriesSnapshot(username: String): List<SeriesEntity>

    @Query("UPDATE series SET tmdbId = :tmdbId, updatedAt = :now WHERE username = :username AND title = :title AND tmdbId != :tmdbId")
    suspend fun updateTmdbId(username: String, title: String, tmdbId: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM series WHERE username = :username AND (backdropUrl != '' OR posterUrl != '') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun firstArtwork(username: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE username = :username AND (backdropUrl != '' OR posterUrl != '') ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun artworks(username: String, limit: Int): List<SeriesEntity>

    @Query("DELETE FROM series")
    suspend fun deleteAll()
}
