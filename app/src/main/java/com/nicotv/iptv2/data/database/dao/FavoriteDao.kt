package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT movieId FROM favorites")
    suspend fun getFavoriteIds(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE movieId = :movieId)")
    suspend fun isFavorite(movieId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE movieId = :movieId")
    suspend fun removeFavorite(movieId: Long)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<FavoriteEntity>)

    /**
     * Remplace tous les favoris en une seule transaction : les observateurs Room
     * ne voient jamais l'état intermédiaire « table vide » (sinon l'étoile et les
     * badges clignotent à chaque synchro de l'état distant).
     */
    @Transaction
    suspend fun replaceAll(favorites: List<FavoriteEntity>) {
        deleteAll()
        if (favorites.isNotEmpty()) insertAll(favorites)
    }
}
