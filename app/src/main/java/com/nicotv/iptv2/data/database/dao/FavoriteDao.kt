package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE itemType = :itemType ORDER BY addedAt DESC")
    fun getFavoritesByType(itemType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT itemId FROM favorites WHERE itemType = :itemType")
    suspend fun getFavoriteIds(itemType: String): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE itemId = :itemId AND itemType = :itemType)")
    suspend fun isFavorite(itemId: Long, itemType: String): Boolean

    @Query("SELECT COUNT(*) FROM favorites")
    fun getCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE itemId = :itemId AND itemType = :itemType")
    suspend fun removeFavorite(itemId: Long, itemType: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}
