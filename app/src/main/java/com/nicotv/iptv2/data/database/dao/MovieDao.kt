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

    // "Déjà dans le catalogue ?" pour le badge ✓/+ des films similaires/filmographie
    // acteur (pas de tmdbId stocké — nos films viennent d'un M3U/Xtream, pas de TMDb).
    @Query("SELECT * FROM movies WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun findByTitle(title: String): MovieEntity?

    @Query("SELECT DISTINCT category FROM movies WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("DELETE FROM movies")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int
}
