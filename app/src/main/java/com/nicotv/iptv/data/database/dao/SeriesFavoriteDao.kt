package com.nicotv.iptv.data.database.dao

import androidx.room.*
import com.nicotv.iptv.data.database.entity.SeriesFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesFavoriteDao {

    @Query("SELECT EXISTS(SELECT 1 FROM series_favorites WHERE seriesId = :seriesId)")
    suspend fun isFavorite(seriesId: Long): Boolean

    @Query("SELECT * FROM series_favorites ORDER BY addedAt DESC")
    fun getAllFavoritesFlow(): Flow<List<SeriesFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(fav: SeriesFavoriteEntity)

    @Query("DELETE FROM series_favorites WHERE seriesId = :seriesId")
    suspend fun remove(seriesId: Long)

    @Query("SELECT seriesId FROM series_favorites")
    suspend fun getAllFavoriteIds(): List<Long>

    @Query("DELETE FROM series_favorites")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favs: List<SeriesFavoriteEntity>)

    @Transaction
    suspend fun replaceAll(favs: List<SeriesFavoriteEntity>) {
        deleteAll()
        if (favs.isNotEmpty()) insertAll(favs)
    }
}
