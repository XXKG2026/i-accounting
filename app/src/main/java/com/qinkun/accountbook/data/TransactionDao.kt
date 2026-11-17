package com.qinkun.accountbook.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun all(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE occurredAt BETWEEN :from AND :to ORDER BY occurredAt DESC")
    fun between(from: Long, to: Long): Flow<List<Transaction>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE type = :type AND occurredAt BETWEEN :from AND :to")
    fun sumByType(type: TxType, from: Long, to: Long): Flow<Long>

    @Query("SELECT category, COALESCE(SUM(amountCents), 0) AS total FROM transactions WHERE type = :type AND occurredAt BETWEEN :from AND :to GROUP BY category ORDER BY total DESC")
    fun groupByCategory(type: TxType, from: Long, to: Long): Flow<List<CategorySum>>

    @Query("SELECT * FROM transactions WHERE rawText = :raw AND occurredAt > :since LIMIT 1")
    suspend fun findDuplicate(raw: String, since: Long): Transaction?

    @Insert
    suspend fun insert(tx: Transaction): Long

    /**
     * 查重 + 插入放在同一个事务里，避免两条并发通知同时通过查重。
     * 返回 -1 表示判定为重复、未插入。
     */
    @androidx.room.Transaction
    suspend fun insertIfNew(tx: Transaction, windowMs: Long = 5 * 60_000): Long {
        val dup = findDuplicate(tx.rawText, tx.occurredAt - windowMs)
        if (dup != null) return -1L
        return insert(tx)
    }

    @Update
    suspend fun update(tx: Transaction)

    @Delete
    suspend fun delete(tx: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

data class CategorySum(val category: String, val total: Long)
