package com.qinkun.accountbook

import com.qinkun.accountbook.ui.AppColor
import com.qinkun.accountbook.ui.BalanceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 结余的配色与文案。
 *
 * 重点是「赤字要看得出来」：以前正负一个颜色，深绿底上白字
 * 「-773.29」和「773.29」扫一眼分不出来。
 */
class BalanceStyleTest {

    @Test
    fun dark_background_highlights_deficit() {
        // 统计页概览卡：深绿底
        assertNotEquals(
            "深色底上赤字必须和正数不同色",
            BalanceStyle.onDark(-77329),
            BalanceStyle.onDark(77329),
        )
        assertEquals("正数保持白字", BalanceStyle.onDark(0), BalanceStyle.onDark(77329))
    }

    @Test
    fun light_background_highlights_deficit() {
        // 首页概览卡：白底
        assertNotEquals(
            "浅色底上赤字必须和正数不同色",
            BalanceStyle.onLight(-77329),
            BalanceStyle.onLight(77329),
        )
        assertEquals("赤字用支出的砖红", AppColor.expense, BalanceStyle.onLight(-1))
        assertEquals("正数用墨色", AppColor.ink, BalanceStyle.onLight(0))
    }

    @Test
    fun deficit_on_dark_is_lighter_than_the_expense_red() {
        // 深绿底上用砖红会发暗，所以深色底走的是更亮的珊瑚红 —— 两者不能是同一个色
        assertNotEquals(
            "深色底的赤字色应该比砖红更亮",
            BalanceStyle.deficitOnDark,
            AppColor.expense,
        )
    }

    @Test
    fun text_shows_minus_only_when_negative() {
        assertEquals("-773.29", BalanceStyle.text(-77329))
        assertEquals("773.29", BalanceStyle.text(77329))
        assertEquals("0.00", BalanceStyle.text(0))
        assertEquals("-0.01", BalanceStyle.text(-1))
    }

    @Test
    fun text_keeps_thousands_separator_out() {
        // 与既有 fmtYuan 一致：不带千分位
        assertEquals("1234567.89", BalanceStyle.text(123456789))
    }
}
