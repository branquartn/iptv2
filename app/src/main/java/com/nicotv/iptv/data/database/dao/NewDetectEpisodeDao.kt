package com.nicotv.iptv.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv.data.database.entity.NewDetectEpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewDetectEpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(entries: List<NewDetectEpisodeEntity>)

    @Query("SELECT fileKey FROM seen_episodes_new")
    fun getAllKeysFlow(): Flow<List<String>>

    @Query("SELECT fileKey FROM seen_episodes_new")
    suspend fun getAllKeys(): List<String>
}
