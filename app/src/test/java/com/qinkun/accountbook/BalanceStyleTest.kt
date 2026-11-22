package com.qinkun.accountbook

import com.qinkun.accountbook.ui.AppColor
import com.qinkun.accountbook.ui.BalanceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 结余的配色与文案。
 *
 * 核心是「赤字要看得出来」。试过两种做法：
 *   · 深绿底上换红字 —— 对比度只有 2.4~3.3:1，大字门槛都勉强
 *   · 整卡换深红底、白字 —— 7.61:1，比正数的 5.55:1 还清楚（采用这个）
 */
class BalanceStyleTest {

    @Test
    fun card_turns_red_when_in_deficit() {
        assertNotEquals(
            "赤字必须换底色，否则和正数分不出来",
            BalanceStyle.cardBg(-73802),
            BalanceStyle.cardBg(73802),
        )
        assertEquals("赤字卡是深红", BalanceStyle.deficitCard, BalanceStyle.cardBg(-1))
        assertEquals("正数卡是深绿", AppColor.accentDeep, BalanceStyle.cardBg(0))
        assertEquals("正数卡是深绿", AppColor.accentDeep, BalanceStyle.cardBg(73802))
    }

    @Test
    fun card_text_stays_white_on_both() {
        // 两种底色上白字都够亮：深红 7.61:1 / 深绿 5.55:1
        assertEquals(androidx.compose.ui.graphics.Color.White, BalanceStyle.onCard)
    }

    @Test
    fun light_background_highlights_deficit() {
        // 首页概览卡是白底，不需要换底，只换字色（砖红 5.68:1）
        assertNotEquals(
            "浅色底上赤字必须和正数不同色",
            BalanceStyle.onLight(-77329),
            BalanceStyle.onLight(77329),
        )
        assertEquals("赤字用支出的砖红", AppColor.expense, BalanceStyle.onLight(-1))
        assertEquals("正数用墨色", AppColor.ink, BalanceStyle.onLight(0))
    }

    @Test
    fun text_shows_minus_only_when_negative() {
        assertEquals("-773.29", BalanceStyle.text(-77329))
        assertEquals("773.29", BalanceStyle.text(77329))
        assertEquals("0.00", BalanceStyle.text(0))
        assertEquals("-0.01", BalanceStyle.text(-1))
        assertEquals("1234567.89", BalanceStyle.text(123456789))
    }

    @Test
    fun zero_is_not_a_deficit() {
        // 结余正好 0 不算亏，卡片保持绿色
        assertEquals(AppColor.accentDeep, BalanceStyle.cardBg(0))
        assertEquals(AppColor.ink, BalanceStyle.onLight(0))
        assertEquals("0.00", BalanceStyle.text(0))
    }
}
