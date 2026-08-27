package com.nicotv.iptv.data.database.dao

import androidx.room.*
import com.nicotv.iptv.data.database.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','DOWNLOADING')")
    suspend fun getActive(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE key = :key AND state = 'COMPLETED'")
    suspend fun getCompleted(key: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE key = :key")
    suspend fun getByKey(key: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAllOnce(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET state = :state, bytesDownloaded = :bytes, bytesTotal = :total, localPath = :path WHERE key = :key")
    suspend fun updateProgress(key: String, state: String, bytes: Long, total: Long, path: String)

    @Query("DELETE FROM downloads WHERE key = :key")
    suspend fun delete(key: String)
}
