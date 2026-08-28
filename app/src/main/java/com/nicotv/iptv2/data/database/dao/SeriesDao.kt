package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY title ASC")
    fun getAllSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun findByTitle(title: String): SeriesEntity?

    @Query("SELECT DISTINCT category FROM series WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** Recherche (écran Search) — cf. MovieDao.searchByTitle, même raison. */
    @Query("SELECT * FROM series WHERE title LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY title ASC LIMIT :limit")
    suspend fun searchByTitle(query: String, limit: Int = 200): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(series: SeriesEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Query("DELETE FROM series")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int
}
