package com.nicotv.iptv.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv.data.database.entity.SeenMovieEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeenMovieDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(entry: SeenMovieEntity)

    @Query("SELECT historyKey FROM seen_movies")
    fun getAllSeenKeysFlow(): Flow<List<String>>
}
