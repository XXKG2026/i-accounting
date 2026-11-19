package com.qinkun.accountbook

import com.qinkun.accountbook.data.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页默认视图的回归测试。
 *
 * 用户的原话是「APP 打开首页，现在当日的记账单」—— 打开就该看见今天的账，
 * 而不是先点一下「日」才看到。这条规则容易被后来的改动悄悄改回去。
 */
class HomePeriodTest {

    @Test
    fun default_view_is_today() {
        val ps = PeriodState()
        assertEquals("打开 App 应停在「日」视图", Period.DAY, ps.period)
        assertEquals("且不偏移，就是今天", 0, ps.offset)
    }

    @Test
    fun default_title_is_computed_not_placeholder() {
        // 标题初始值必须和真实区间算出来的一致，否则首帧会闪一下错的日期
        val expected = com.qinkun.accountbook.data.Repo.rangeTitle(Period.DAY, 0)
        assertTrue("日视图标题应含年月日，实际是「$expected」",
            expected.contains("年") && expected.contains("月") && expected.contains("日"))
    }
}
