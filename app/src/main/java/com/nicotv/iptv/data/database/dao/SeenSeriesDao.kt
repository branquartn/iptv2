package com.nicotv.iptv.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nicotv.iptv.data.database.entity.SeenSeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeenSeriesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markSeen(entry: SeenSeriesEntity)

    @Query("SELECT name FROM seen_series")
    fun getAllNamesFlow(): Flow<List<String>>

    @Query("SELECT name FROM seen_series")
    suspend fun getAllNames(): List<String>
}
