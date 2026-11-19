package com.qinkun.accountbook.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

enum class Period(val label: String) {
    DAY("日"), WEEK("周"), MONTH("月"), YEAR("年")
}

class Repo(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.txDao()
    private val logDao = db.logDao()

    fun all(): Flow<List<Transaction>> = dao.all()
    fun between(from: Long, to: Long): Flow<List<Transaction>> = dao.between(from, to)
    fun sumExpense(from: Long, to: Long): Flow<Long> = dao.sumByType(TxType.EXPENSE, from, to)
    fun sumIncome(from: Long, to: Long): Flow<Long> = dao.sumByType(TxType.INCOME, from, to)
    fun byCategory(type: TxType, from: Long, to: Long): Flow<List<CategorySum>> = dao.groupByCategory(type, from, to)

    suspend fun add(tx: Transaction) = dao.insert(tx)
    suspend fun addAll(list: List<Transaction>) = dao.insertAll(list)
    suspend fun allOnce(): List<Transaction> = dao.allOnce()
    suspend fun addIfNew(tx: Transaction): Long = dao.insertIfNew(tx)
    suspend fun remove(tx: Transaction) = dao.delete(tx)
    suspend fun update(tx: Transaction) = dao.update(tx)
    suspend fun findDuplicate(raw: String, since: Long) = dao.findDuplicate(raw, since)

    suspend fun lastTxAt(): Long? = dao.lastTxAt()

    /** 商户→分类的记忆；null 表示这个商户还没被教过 */
    suspend fun learnedCategory(merchant: String): String? =
        if (merchant.isBlank()) null else dao.learnedCategory(merchant)

    // ---- 补录辅助 ----
    suspend fun frequentMerchants(limit: Int = 12): List<String> = dao.frequentMerchants(limit)
    suspend fun recentCategories(type: TxType, since: Long): List<String> = dao.recentCategories(type, since)

    // ---- 抓取日志 ----
    fun captureLogs(limit: Int = 100) = logDao.recent(limit)
    suspend fun addCaptureLog(log: CaptureLog) = logDao.insert(log)
    suspend fun pruneCaptureLogs(keepDays: Int = 7) {
        logDao.prune(System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000)
    }
    suspend fun clearCaptureLogs() = logDao.clear()

    companion object {
        /**
         * 按周期算起止毫秒。offset 为 0 表示当前周期，-1 上一个周期。
         */
        fun rangeFor(
            period: Period,
            offset: Int,
            now: Long = System.currentTimeMillis(),
        ): Pair<Long, Long> {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            // 先归零到当天 0 点
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)

            return when (period) {
                Period.DAY -> {
                    c.add(Calendar.DAY_OF_YEAR, offset)
                    val from = c.timeInMillis
                    c.add(Calendar.DAY_OF_YEAR, 1)
                    from to c.timeInMillis - 1
                }
                Period.WEEK -> {
                    // 归到本周一
                    val dow = c.get(Calendar.DAY_OF_WEEK)
                    val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
                    c.add(Calendar.DAY_OF_YEAR, -back + offset * 7)
                    val from = c.timeInMillis
                    c.add(Calendar.DAY_OF_YEAR, 7)
                    from to c.timeInMillis - 1
                }
                Period.MONTH -> {
                    c.set(Calendar.DAY_OF_MONTH, 1)
                    c.add(Calendar.MONTH, offset)
                    val from = c.timeInMillis
                    c.add(Calendar.MONTH, 1)
                    from to c.timeInMillis - 1
                }
                Period.YEAR -> {
                    c.set(Calendar.DAY_OF_YEAR, 1)
                    c.add(Calendar.YEAR, offset)
                    val from = c.timeInMillis
                    c.add(Calendar.YEAR, 1)
                    from to c.timeInMillis - 1
                }
            }
        }

        /**
         * 统计用的区间：翻到还没到的周期（比如 2027 年）时给一个空区间。
         *
         * 为什么不直接把上界压到「现在」：`rangeFlow` 只在切换周期或跨天时重算，
         * 上界一旦定死成某一刻，那一刻之后新记的账就会被挡在统计之外 ——
         * 下午记的账晚上看不见，比空页面更糟。
         *
         * 所以只堵「整段都在未来」这种情况：区间起点已经晚于现在，
         * 说明这一年/月/日还没开始，本来就该是 0。
         */
        fun rangeForStats(
            period: Period,
            offset: Int,
            now: Long = System.currentTimeMillis(),
        ): Pair<Long, Long> {
            val (from, to) = rangeFor(period, offset, now)
            return if (from > now) now to now - 1 else from to to
        }

        /** 区间的人类可读标题，如「2026 年 11 月」「11 月 10 日 — 11 月 16 日」 */
        fun rangeTitle(period: Period, offset: Int): String {
            val (from, to) = rangeFor(period, offset)
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            val c = Calendar.getInstance().apply { timeInMillis = from }
            return when (period) {
                Period.DAY -> java.text.SimpleDateFormat("yyyy 年 M 月 d 日", java.util.Locale.CHINA).format(c.time)
                Period.WEEK -> {
                    val c2 = Calendar.getInstance().apply { timeInMillis = to }
                    java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(c.time) + " — " +
                        java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(c2.time)
                }
                Period.MONTH -> java.text.SimpleDateFormat("yyyy 年 M 月", java.util.Locale.CHINA).format(c.time)
                Period.YEAR -> c.get(Calendar.YEAR).toString() + " 年"
            }
        }
    }
}
