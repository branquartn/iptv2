package com.nicotv.iptv2.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv2.data.database.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY sortOrder ASC, name ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT DISTINCT category FROM channels WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** Lecture ponctuelle des noms — cf. PlaylistRepository.
     * getAvailableContentLanguages (découverte des codes langue, une seule
     * fois à l'ouverture du sélecteur dans Réglages, pas un Flow). */
    @Query("SELECT name FROM channels")
    suspend fun getAllChannelNamesOnce(): List<String>

    /** Recherche (écran Search) — cf. MovieDao.searchByTitle, même raison. */
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY name ASC LIMIT :limit")
    suspend fun searchByName(query: String, limit: Int = 200): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int
}
