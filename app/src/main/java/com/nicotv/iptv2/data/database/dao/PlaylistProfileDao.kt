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

    /** Lecture ponctuelle (pas un Flow) — pour écrire la copie de secours
     * SharedPreferences après chaque modification (cf. ProfileBackupPrefs). */
    @Query("SELECT * FROM playlist_profiles ORDER BY lastUsedAt DESC")
    suspend fun getAllOnce(): List<PlaylistProfileEntity>

    @Query("SELECT COUNT(*) FROM playlist_profiles")
    suspend fun countProfiles(): Int

    @Query("SELECT * FROM playlist_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: PlaylistProfileEntity): Long

    @Query("UPDATE playlist_profiles SET lastUsedAt = :now WHERE id = :id")
    suspend fun touchLastUsed(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlist_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
