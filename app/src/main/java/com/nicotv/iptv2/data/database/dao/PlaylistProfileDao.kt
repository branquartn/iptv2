package com.nicotv.iptv2.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistProfileDao {
    @Query("SELECT * FROM playlist_profiles ORDER BY lastUsedAt DESC")
    fun getAll(): Flow<List<PlaylistProfileEntity>>

    @Query("SELECT * FROM playlist_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: PlaylistProfileEntity): Long

    @Query("UPDATE playlist_profiles SET lastUsedAt = :now WHERE id = :id")
    suspend fun touchLastUsed(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlist_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
