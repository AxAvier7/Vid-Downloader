package com.ytdownloader.app.data

import android.content.Context
import com.ytdownloader.app.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

object DownloadRepository {

    @Volatile
    private var database: DownloadsDatabase? = null

    fun get(context: Context): DownloadsDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: DownloadsDatabase.get(context.applicationContext).also { database = it }
        }
    }

    fun observeDownloads(context: Context): Flow<List<DownloadEntity>> = get(context).dao().observeAll()

    suspend fun insert(context: Context, entity: DownloadEntity): Long = get(context).dao().insert(entity)

    suspend fun update(context: Context, entity: DownloadEntity) = get(context).dao().update(entity)

    suspend fun updateProgress(context: Context, id: Long, status: DownloadStatus, progress: Float) {
        get(context).dao().updateProgress(id, status.name, progress)
    }

    suspend fun setError(context: Context, id: Long, status: DownloadStatus, error: String) {
        get(context).dao().setError(id, status.name, error)
    }
}