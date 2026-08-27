package com.nicotv.iptv2.data.database.dao

import androidx.room.*
import com.nicotv.iptv2.data.database.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC")
    fun getRecentHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history")
    fun getAllHistory(): Flow<List<WatchHistoryEntity>>

    /** Snapshot ponctuel (pas un Flow) : utilisé pour fusionner avant un replaceAll
     * (cf. syncRemoteState) sans dépendre du timing d'émission du Flow. */
    @Query("SELECT * FROM watch_history")
    suspend fun getAllHistorySnapshot(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE historyKey = :key LIMIT 1")
    suspend fun getPosition(key: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE historyKey IN (:keys)")
    suspend fun getPositions(keys: List<String>): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosition(history: WatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(entries: List<WatchHistoryEntity>)

    @Query("DELETE FROM watch_history WHERE historyKey = :key")
    suspend fun removeHistory(key: String)

    @Query("DELETE FROM watch_history WHERE historyKey IN (:keys)")
    suspend fun removeHistories(keys: List<String>)

    @Query("DELETE FROM watch_history")
    suspend fun deleteAll()

    /**
     * Remplace tout l'historique en une seule transaction : les observateurs Room
     * ne voient jamais l'état intermédiaire « table vide » (sinon les barres de
     * reprise et les badges NOUVEAU clignotent à chaque synchro de l'état distant).
     */
    @Transaction
    suspend fun replaceAll(entries: List<WatchHistoryEntity>) {
        deleteAll()
        if (entries.isNotEmpty()) saveAll(entries)
    }
}
