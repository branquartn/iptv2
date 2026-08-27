package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.MovieEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies WHERE username = :username ORDER BY title ASC")
    fun getAllMovies(username: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE username = :username ORDER BY title ASC")
    suspend fun getAllMoviesSnapshot(username: String): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE title LIKE '%' || :query || '%' AND username = :username ORDER BY title ASC")
    suspend fun searchMovies(query: String, username: String): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun getMovieById(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE username = :username AND id IN (:ids)")
    suspend fun getMoviesByIds(username: String, ids: List<Long>): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE username = :username AND tmdbId = :tmdbId LIMIT 1")
    suspend fun getMovieByTmdbId(username: String, tmdbId: Int): MovieEntity?

    @Query("UPDATE movies SET addedAt = 0 WHERE id = :id AND addedAt > 0")
    suspend fun markSeen(id: Long)

    @Query("SELECT * FROM movies WHERE username = :username AND addedAt = 0 AND tmdbId > 0")
    suspend fun getSeenMovies(username: String): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(movie: MovieEntity): Long

    @Query("SELECT COUNT(*) FROM movies WHERE username = :username")
    suspend fun countForUser(username: String): Int

    @Query("DELETE FROM movies WHERE title NOT IN (:activeTitles) AND username = :username")
    suspend fun deleteObsoleteMovies(activeTitles: List<String>, username: String)

    @Query("DELETE FROM movies WHERE username = :username")
    suspend fun deleteForUser(username: String)

    @Query("SELECT * FROM movies WHERE username = :username AND (backdropUrl != '' OR posterUrl != '') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun firstArtwork(username: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE username = :username AND (backdropUrl != '' OR posterUrl != '') ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun artworks(username: String, limit: Int): List<MovieEntity>

    @Query("DELETE FROM movies")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int
}
