package com.qinkun.accountbook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureLogDao {

    @Insert
    suspend fun insert(log: CaptureLog): Long

    @Query("SELECT * FROM capture_logs ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<CaptureLog>>

    /** 只保留最近 N 天，避免无限增长 */
    @Query("DELETE FROM capture_logs WHERE at < :before")
    suspend fun prune(before: Long): Int

    @Query("DELETE FROM capture_logs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM capture_logs WHERE at >= :since AND success = 1")
    suspend fun countSuccessSince(since: Long): Int
}
