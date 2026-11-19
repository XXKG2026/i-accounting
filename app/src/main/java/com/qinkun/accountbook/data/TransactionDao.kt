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

    /**
     * 同一笔钱可能被两条通道各抓一次：
     *   · 无障碍读付款结果页 → 「支付宝 支付成功 支付成功￥71.00 天外山 …」
     *   · 通知监听读通知栏   → 「交易提醒 你有一笔71.00元的支出，点此查看详情。」
     * 两者原文完全不同，按文字判重会漏，于是同金额同来源就近再判一次。
     */
    @Query(
        """SELECT * FROM transactions
           WHERE amountCents = :amount AND sourceApp = :app
             AND occurredAt BETWEEN :from AND :to
           ORDER BY ABS(occurredAt - :center) LIMIT 1"""
    )
    suspend fun findTwin(amount: Long, app: String, from: Long, to: Long, center: Long): Transaction?

    @Insert
    suspend fun insert(tx: Transaction): Long

    /**
     * 查重 + 插入放在同一个事务里，避免两条并发通知同时通过查重。
     * 返回 -1 表示判定为重复、未插入。
     */
    @androidx.room.Transaction
    suspend fun insertIfNew(tx: Transaction, windowMs: Long = 5 * 60_000): Long {
        // 第一级：原文相同 → 肯定是同一条通知的重发
        if (findDuplicate(tx.rawText, tx.occurredAt - windowMs) != null) return -1L

        // 第二级：无障碍和通知各抓一次 → 同金额、同来源、时间相邻，算同一笔。
        // 窗口取 ±90 秒：页面重绘和通知到达通常相差几秒；再宽就会吞掉
        // 「同一家店连买两次同样价钱」这种真事。
        val twin = findTwin(
            tx.amountCents, tx.sourceApp,
            tx.occurredAt - TWIN_WINDOW_MS, tx.occurredAt + TWIN_WINDOW_MS, tx.occurredAt,
        )
        if (twin != null) {
            // 已存在的那条可能信息更少（比如通知版没有商户名），用新的补全它，
            // 但绝不新增一条。
            val betterMerchant = tx.merchant.isNotBlank() && tx.merchant != "手动记账" &&
                (twin.merchant.isBlank() || twin.merchant == "手动记账")
            if (betterMerchant) {
                update(
                    twin.copy(
                        merchant = tx.merchant,
                        category = tx.category,
                        rawText = tx.rawText.ifBlank { twin.rawText },
                    )
                )
            }
            return -1L
        }
        return insert(tx)
    }

    companion object {
        /** 双通道合并窗口：±90 秒 */
        const val TWIN_WINDOW_MS = 90_000L
    }

    @Update
    suspend fun update(tx: Transaction)

    @Delete
    suspend fun delete(tx: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 常用商户 —— 手动补录时给个快捷列表。
     * 按"记过几次"排序，同次数时取最近用过的。
     */
    @Query(
        """SELECT merchant FROM transactions
           WHERE merchant != '' AND merchant != '手动记账'
           GROUP BY merchant
           ORDER BY COUNT(*) DESC, MAX(occurredAt) DESC
           LIMIT :limit"""
    )
    suspend fun frequentMerchants(limit: Int = 12): List<String>

    /**
     * 商户分类记忆：这个商户以前被分到过哪一类，就沿用哪一类。
     *
     * 为什么要它：像「天外山」这种供应商名字，词库里不可能有，只能靠
     * 主人手动改一次来教。查的是历史流水里该商户出现最多的分类，
     * 手动改过的记录本身就是"教学样本"。
     *
     * 排除「其他」：那是兜底的默认值，不是主人主动选的。
     */
    @Query(
        """SELECT category FROM transactions
           WHERE merchant = :merchant AND category != '' AND category != '其他'
             AND sourceApp != '手动'
           GROUP BY category
           ORDER BY COUNT(*) DESC, MAX(occurredAt) DESC
           LIMIT 1"""
    )
    suspend fun learnedCategory(merchant: String): String?

    /** 导出用：一次性拿全部流水（列表页用的是 Flow，这里要快照） */
    @Query("SELECT * FROM transactions ORDER BY occurredAt")
    suspend fun allOnce(): List<Transaction>

    /** 最后一条记账的时间 —— 判断"这次离开支付 App 之后有没有已经记上" */
    @Query("SELECT MAX(occurredAt) FROM transactions")
    suspend fun lastTxAt(): Long?

    /**
     * 最近用过的分类（按笔数降序），用来把高频分类排到选择列表前面。
     */
    @Query(
        """SELECT category FROM transactions
           WHERE type = :type AND occurredAt >= :since
           GROUP BY category
           ORDER BY COUNT(*) DESC"""
    )
    suspend fun recentCategories(type: TxType, since: Long): List<String>
}

data class CategorySum(val category: String, val total: Long)
