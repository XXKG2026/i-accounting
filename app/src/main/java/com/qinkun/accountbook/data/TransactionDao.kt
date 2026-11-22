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
           ORDER BY ABS(occurredAt - :center) LIMIT 3"""
    )
    suspend fun findTwinCandidates(amount: Long, app: String, from: Long, to: Long, center: Long): List<Transaction>

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

        // 第二级：无障碍和通知各抓一次 → 同金额、同来源、时间相邻，算同一笔。
        // 窗口 30 秒足够覆盖「页面渲染」和「通知到达」的时间差；
        // 再放宽就会吞掉真实连击（菜市场连买两把同样价钱的菜）。
        val twin = findTwinCandidates(
            tx.amountCents, tx.sourceApp,
            tx.occurredAt - TWIN_WINDOW_MS, tx.occurredAt + TWIN_WINDOW_MS, tx.occurredAt,
        ).firstOrNull { old ->
            // 收支方向不同绝不是同一笔：2 秒内「付 100」和「退 100」会被
            // SAME_MOMENT 分支合并成一条，等于把退款吞了
            if (old.type != tx.type) return@firstOrNull false
            val gap = kotlin.math.abs(old.occurredAt - tx.occurredAt)
            when {
                // 同一时刻 + 同金额：无障碍和通知各抓一次，或通知重发。
                // 这种情况下必然是同一笔，不管商户名有没有、对不对得上。
                gap <= SAME_MOMENT_MS -> true

                // 时间稍远：只有两边商户名对得上才算同一笔，
                // 否则宁可当两笔记（菜市场连买两把 5 元的菜）
                isRealMerchant(old.merchant) && isRealMerchant(tx.merchant) ->
                    sameMerchantFamily(old.merchant, tx.merchant)

                else -> gap <= NEAR_SIMULTANEOUS_MS
            }
        }

        if (twin != null) {
            // 已存在的那条可能信息更少（通知版没有商户名），用新的补全，
            // 但绝不新增一条。原文留信息量更大的那份，方便事后核对。
            val betterMerchant = isRealMerchant(tx.merchant) && !isRealMerchant(twin.merchant)
            val betterText = if (tx.rawText.length > twin.rawText.length) tx.rawText else twin.rawText
            update(
                twin.copy(
                    merchant = if (betterMerchant) tx.merchant else twin.merchant,
                    category = if (betterMerchant) tx.category else twin.category,
                    rawText = betterText,
                )
            )
            return -1L
        }
        return insert(tx)
    }

    /** 有真实商户名（不是空、不是「手动记账」、不是「XX（未识别商户）」） */
    private fun isRealMerchant(m: String): Boolean =
        m.isNotBlank() && m != "手动记账" && !m.contains("未识别")

    /**
     * 两条记录能不能算同一商户。
     *
     * 规则：
     *   · 任一方没有真实商户名（无障碍抓到了、通知没抓到）→ 算同一商户
     *   · 两方都有，且互相包含（「星巴克」vs「星巴克(高新店)」）→ 算同一商户
     *   · 两方都有但完全不同 → 不是同一笔（可能是连买两次）
     */
    private fun sameMerchantFamily(a: String, b: String): Boolean {
        if (!isRealMerchant(a) || !isRealMerchant(b)) return true
        return a.contains(b) || b.contains(a)
    }

    companion object {
        /** 双通道合并窗口：±30 秒 */
        const val TWIN_WINDOW_MS = 30_000L

        /** 同一时刻（±2 秒）+ 同金额 → 必然是重复抓取，直接合并 */
        const val SAME_MOMENT_MS = 2_000L

        /** 两条都没有商户名时，只有「几乎同时」才敢判成同一笔 */
        const val NEAR_SIMULTANEOUS_MS = 8_000L
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
