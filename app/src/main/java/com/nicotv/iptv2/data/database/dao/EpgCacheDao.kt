package com.nicotv.iptv2.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv2.data.database.entity.EpgCacheEntity

@Dao
interface EpgCacheDao {
    @Query("SELECT * FROM epg_cache WHERE channelId = :channelId LIMIT 1")
    suspend fun getByChannelId(channelId: Long): EpgCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EpgCacheEntity)

    /** Vidée à chaque rechargement de catalogue (les id de chaîne sont
     * réattribués — cf. PlaylistRepository) et depuis l'écran Réglages
     * ("Vider le cache EPG"). */
    @Query("DELETE FROM epg_cache")
    suspend fun clearAll()
}
