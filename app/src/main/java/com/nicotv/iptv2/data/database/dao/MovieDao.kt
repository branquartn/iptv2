package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.MovieEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies ORDER BY title ASC")
    fun getAllMovies(): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun getMovieById(id: Long): MovieEntity?

    @Query("SELECT DISTINCT category FROM movies WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("DELETE FROM movies")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int
}
