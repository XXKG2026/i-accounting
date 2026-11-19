package com.qinkun.accountbook

import com.qinkun.accountbook.data.Period
import com.qinkun.accountbook.data.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 统计区间的边界：翻到未来不能有数据，但当期不能把「刚记的账」挡在外面。
 */
class StatsRangeTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun future_year_gives_empty_range() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.YEAR, 1, now)   // 2027 年
        assertTrue("起点应该晚于终点（空区间）", from > to)
    }

    @Test
    fun future_month_gives_empty_range() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.MONTH, 1, now)  // 12 月
        assertTrue("下个月还没到，应为空", from > to)
    }

    @Test
    fun future_day_gives_empty_range() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.DAY, 1, now)    // 明天
        assertTrue("明天还没到，应为空", from > to)
    }

    @Test
    fun current_year_ends_today_not_december() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.YEAR, 0, now)

        // 起点：1 月 1 日 0 点
        val c = Calendar.getInstance().apply { timeInMillis = from }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, c.get(Calendar.MONTH))
        assertEquals(1, c.get(Calendar.DAY_OF_MONTH))

        // 终点：今天（11 月 19 日）收尾，不是 12 月 31 日 —— 这一年还没过完
        val e = Calendar.getInstance().apply { timeInMillis = to }
        assertEquals(2026, e.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, e.get(Calendar.MONTH))
        assertEquals(19, e.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, e.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, e.get(Calendar.MINUTE))
    }

    @Test
    fun current_year_excludes_the_rest_of_december() {
        // 12 月的数据不该算进「2026 年」—— 因为还没到
        val now = at(2026, 11, 19, 15)
        val (_, to) = Repo.rangeForStats(Period.YEAR, 0, now)
        val decemberFirst = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue("上界必须停在今天，不能跨进 12 月", to < decemberFirst)
    }

    @Test
    fun current_month_ends_today() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.MONTH, 0, now)
        val f = Calendar.getInstance().apply { timeInMillis = from }
        val t = Calendar.getInstance().apply { timeInMillis = to }
        assertEquals(1, f.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, t.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun finished_year_is_not_trimmed() {
        // 2025 年已经过完，12 月 31 日的数据该算
        val now = at(2026, 11, 19, 15)
        val (_, to) = Repo.rangeForStats(Period.YEAR, -1, now)
        val t = Calendar.getInstance().apply { timeInMillis = to }
        assertEquals(2025, t.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, t.get(Calendar.MONTH))
        assertEquals(31, t.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun current_day_ends_after_now() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.DAY, 0, now)
        assertTrue("今天 15 点之后记的账也得算进去", to > now)
    }

    @Test
    fun past_year_is_untouched() {
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeForStats(Period.YEAR, -1, now)
        val c = Calendar.getInstance().apply { timeInMillis = from }
        assertEquals(2025, c.get(Calendar.YEAR))
        assertTrue("过去的区间不该被裁剪", to > from)
    }

    @Test
    fun title_side_ignores_the_clamp() {
        // 标题走 rangeFor（不裁剪），保证「2027 年」照样能显示出来
        val now = at(2026, 11, 19, 15)
        val (from, to) = Repo.rangeFor(Period.YEAR, 1, now)
        assertTrue("标题用的区间是完整的自然年", to > from)
        val c = Calendar.getInstance().apply { timeInMillis = from }
        assertEquals(2027, c.get(Calendar.YEAR))
    }
}
