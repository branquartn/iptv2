package com.nicotv.iptv.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv.data.database.entity.SeenEpisodeEntity

@Dao
interface SeenEpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(entry: SeenEpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeenAll(entries: List<SeenEpisodeEntity>)

    @Query("SELECT fileKey FROM seen_episodes")
    suspend fun getAllSeenFileKeys(): List<String>
}
