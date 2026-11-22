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

    /** 同金额、时间相邻的候选（不限来源 App，由 [TwinRules] 判定是不是同一笔） */
    @Query(
        """SELECT * FROM transactions
           WHERE amountCents = :amount
             AND occurredAt BETWEEN :from AND :to
           ORDER BY ABS(occurredAt - :center) LIMIT 5"""
    )
    suspend fun findTwinCandidates(amount: Long, from: Long, to: Long, center: Long): List<Transaction>

    @Insert
    suspend fun insert(tx: Transaction): Long

    /**
     * 批量插入。导入几千条历史账单时逐条 insert 会是几千个事务，
     * 慢到用户以为卡死；一次走一个事务，几秒就完。
     */
    @Insert
    suspend fun insertAll(list: List<Transaction>): List<Long>

    /**
     * 查重 + 插入放在同一个事务里，避免两条并发通知同时通过查重。
     * 返回 -1 表示判定为重复、未插入。
     */
    @androidx.room.Transaction
    suspend fun insertIfNew(tx: Transaction, windowMs: Long = 5 * 60_000): Long {
        // 第一级：原文相同 → 肯定是同一条通知的重发
        if (findDuplicate(tx.rawText, tx.occurredAt - windowMs) != null) return -1L

        // 第二级：两条通道各抓一次（同 App 的页面+通知，或淘宝页面+支付宝通知）
        val twin = findTwinCandidates(
            tx.amountCents,
            tx.occurredAt - TwinRules.TWIN_WINDOW_MS, tx.occurredAt + TwinRules.TWIN_WINDOW_MS, tx.occurredAt,
        ).firstOrNull { TwinRules.isTwin(it, tx) }

        if (twin != null) {
            // 绝不新增一条；已存在的那条用新的补全
            update(TwinRules.merge(twin, tx))
            return -1L
        }
        return insert(tx)
    }

    /**
     * v4.1 历史修复用：旧版把「你已成功收款」抠成「你已成功」当商户名，
     * 这里把这些收入记录捞出来重算商户名和备注。
     */
    @Query(
        """SELECT * FROM transactions
           WHERE type = :type AND sourceApp != '手动'
             AND (merchant LIKE '%成功%' OR merchant LIKE '你已%' OR merchant LIKE '已%'
                  OR merchant LIKE '%未识别%' OR merchant IN ('支付宝', '微信'))"""
    )
    suspend fun suspiciousIncomeMerchants(type: TxType): List<Transaction>

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
     *
     * 不排除「手动」：手动补一笔「天外山 → 餐饮」，下次自动抓到天外山就该沿用餐饮。
     * 之前排除了手动记录，导致「手动教一次」根本不管用。
     *
     * v4.0：按收支方向分开记。以前不分方向，在京东买过东西（购物）之后，
     * 京东的退款也会沿用「购物」—— 而「购物」根本不是收入分类，统计页收入侧
     * 会冒出一个不存在的分类，编辑框里也选不中。
     */
    @Query(
        """SELECT category FROM transactions
           WHERE merchant = :merchant AND type = :type
             AND category != '' AND category != '其他'
           GROUP BY category
           ORDER BY COUNT(*) DESC, MAX(occurredAt) DESC
           LIMIT 1"""
    )
    suspend fun learnedCategory(merchant: String, type: TxType): String?

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
