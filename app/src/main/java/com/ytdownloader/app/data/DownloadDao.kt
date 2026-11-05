package com.ytdownloader.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progress: Float)

    @Query("UPDATE downloads SET progress = :progress WHERE id = :id AND status = :status")
    suspend fun updateProgressIf(id: Long, progress: Float, status: String)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setError(id: Long, status: String, error: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id AND status IN (:fromStatuses)")
    suspend fun setStatusIf(id: Long, status: String, fromStatuses: List<String>): Int

    @Query("UPDATE downloads SET status = :newStatus, error = :error WHERE status IN (:statuses) AND createdAt < :before")
    suspend fun failStuck(statuses: List<String>, newStatus: String, error: String, before: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}