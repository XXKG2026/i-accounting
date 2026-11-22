package com.qinkun.accountbook.data

import java.util.Calendar

/** 某个维度（分类 / 来源 App / 商户）下的一组合计 */
data class GroupSum(val key: String, val total: Long, val count: Int)

/** 趋势图上的一根柱子 */
data class TrendPoint(val label: String, val expense: Long, val income: Long)

/**
 * 统计页的纯计算逻辑（无 IO，方便单测）。
 *
 * 为什么不全交给 SQL：统计页本来就拿到了当期全部流水（首页/明细同一份），
 * 一年也就几千条，内存里分组比为每个维度各开一条 Flow 查询更省、也更好测。
 */
object StatsCalc {

    const val DAY_MS = 24L * 60 * 60 * 1000

    private fun group(
        list: List<Transaction>,
        type: TxType,
        keyOf: (Transaction) -> String,
    ): List<GroupSum> =
        list.filter { it.type == type }
            .groupBy { keyOf(it).ifBlank { "未知" } }
            .map { (k, v) -> GroupSum(k, v.sumOf { it.amountCents }, v.size) }
            // 金额相同时按名字排，保证每次渲染顺序一致（饼图颜色不跳）
            .sortedWith(compareByDescending<GroupSum> { it.total }.thenBy { it.key })

    fun byCategory(list: List<Transaction>, type: TxType): List<GroupSum> =
        group(list, type) { it.category }

    /** 按来源 App：美团、淘宝、微信……手动记的统一归到「手动」 */
    fun bySource(list: List<Transaction>, type: TxType): List<GroupSum> =
        group(list, type) { it.sourceApp.ifBlank { "手动" } }

    /** 商户排行：没识别出商户的不参与排名，否则「XX（未识别商户）」常年霸榜 */
    fun topMerchants(list: List<Transaction>, type: TxType, limit: Int = 10): List<GroupSum> =
        group(list.filter { isRealMerchant(it.merchant) }, type) { it.merchant }.take(limit)

    private fun isRealMerchant(m: String): Boolean =
        m.isNotBlank() && m != "手动记账" && !m.contains("未识别")

    /**
     * 趋势柱状图的分桶。
     *   日 → 24 小时；周 → 周一到周日；月 → 当月每天；年 → 12 个月
     *
     * [periodStart] 必须是整个周期的起点（Repo.rangeFor 的 first），
     * 不能用统计区间 —— 统计区间会被截到今天，但柱子要画满整个周期。
     */
    fun trend(period: Period, periodStart: Long, list: List<Transaction>): List<TrendPoint> {
        val start = Calendar.getInstance().apply { timeInMillis = periodStart }
        val labels: List<String> = when (period) {
            Period.DAY -> (0 until 24).map { "$it" }
            Period.WEEK -> listOf("一", "二", "三", "四", "五", "六", "日")
            Period.MONTH -> (1..start.getActualMaximum(Calendar.DAY_OF_MONTH)).map { "$it" }
            Period.YEAR -> (1..12).map { "${it}月" }
        }
        val exp = LongArray(labels.size)
        val inc = LongArray(labels.size)
        val c = Calendar.getInstance()
        for (t in list) {
            if (t.occurredAt < periodStart) continue
            c.timeInMillis = t.occurredAt
            val idx = when (period) {
                Period.DAY -> c.get(Calendar.HOUR_OF_DAY)
                Period.WEEK -> ((t.occurredAt - periodStart) / DAY_MS).toInt()
                Period.MONTH ->
                    if (c.get(Calendar.MONTH) == start.get(Calendar.MONTH)) c.get(Calendar.DAY_OF_MONTH) - 1 else -1
                Period.YEAR ->
                    if (c.get(Calendar.YEAR) == start.get(Calendar.YEAR)) c.get(Calendar.MONTH) else -1
            }
            if (idx !in labels.indices) continue
            if (t.type == TxType.EXPENSE) exp[idx] += t.amountCents else inc[idx] += t.amountCents
        }
        return labels.mapIndexed { i, l -> TrendPoint(l, exp[i], inc[i]) }
    }

    /**
     * 统计区间实际经过了几天（给「日均」用）。区间为空返回 0。
     * 按自然日数，跨了几个日历日就算几天，不足一天按一天。
     */
    fun elapsedDays(from: Long, to: Long): Int {
        if (to < from) return 0
        val a = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val b = Calendar.getInstance().apply {
            timeInMillis = to
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // 都对齐到中午再相减，避开夏令时那一小时的误差
        return (Math.round((b.timeInMillis - a.timeInMillis).toDouble() / DAY_MS) + 1).toInt()
    }

    /**
     * 明细页的搜索 + 筛选。
     * [query] 匹配商户、备注、分类、来源 App、金额文本；[type]/[source] 为 null 表示不限。
     */
    fun filter(
        list: List<Transaction>,
        query: String,
        type: TxType? = null,
        source: String? = null,
    ): List<Transaction> {
        val q = query.trim()
        return list.filter { t ->
            (type == null || t.type == type) &&
                (source == null || t.sourceApp.ifBlank { "手动" } == source) &&
                (q.isEmpty() ||
                    t.merchant.contains(q, ignoreCase = true) ||
                    t.note.contains(q, ignoreCase = true) ||
                    t.category.contains(q) ||
                    t.sourceApp.contains(q, ignoreCase = true) ||
                    t.yuanText.contains(q))
        }
    }
}
