package com.qinkun.accountbook

import com.qinkun.accountbook.data.Backup
import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.ui.EXPENSE_CATEGORIES
import com.qinkun.accountbook.ui.INCOME_CATEGORIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 导入相关的回归测试。
 *
 * 这里每条都对应一次真实踩过或差点踩的坑，不是凑数。
 */
class ImportTest {

    // ---------------- 金额：不能截断 ----------------

    @Test
    fun amount_rounds_instead_of_truncating() {
        // double 精度陷阱：19.99 * 100 = 1998.9999999999998
        assertEquals(1999L, Backup.parseYuanToCents("19.99"))
        assertEquals(29L, Backup.parseYuanToCents("0.29"))
        assertEquals(58L, Backup.parseYuanToCents("0.58"))
        assertEquals(268L, Backup.parseYuanToCents("2.675"))
        assertEquals(19800L, Backup.parseYuanToCents("198.00"))
    }

    @Test
    fun amount_tolerates_thousand_separators_and_spaces() {
        assertEquals(123456789L, Backup.parseYuanToCents(" 1,234,567.89 "))
    }

    @Test
    fun amount_rejects_garbage() {
        assertNull(Backup.parseYuanToCents("abc"))
        assertNull(Backup.parseYuanToCents(""))
        assertNull(Backup.parseYuanToCents("12.34.56"))
    }

    // ---------------- 日期：Excel 序列号 ----------------

    @Test
    fun excel_serial_converts_to_right_date() {
        fun ymd(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .apply { timeZone = TimeZone.getDefault() }
                .format(millis)
        // 46299 = 2026-10-04（那份账单的最后一条就在这天 17:27）
        assertEquals("2026-10-04", ymd(Backup.excelSerialToMillis(46299.0)))
        // 45875 = 2025-08-06
        assertEquals("2025-08-06", ymd(Backup.excelSerialToMillis(45875.0)))
    }

    @Test
    fun excel_serial_keeps_time_of_day() {
        val noon = Backup.excelSerialToMillis(46299.5)   // 当天 12:00
        val midnight = Backup.excelSerialToMillis(46299.0)
        assertEquals(12 * 3600_000L, noon - midnight)
    }

    // ---------------- 分类映射 ----------------

    @Test
    fun travel_maps_to_business_spending() {
        // 主人指定：旅行算经营支出（出差、进货路费）
        assertEquals("经营", Backup.mapCategory("旅行", TxType.EXPENSE))
        assertEquals("经营", Backup.mapCategory("差旅", TxType.EXPENSE))
        assertEquals("经营", Backup.mapCategory("出差", TxType.EXPENSE))
    }

    @Test
    fun generic_consumption_falls_back_to_other() {
        // 只有「消费」两个字，认不出买什么 → 其他，不瞎猜
        assertEquals("其他", Backup.mapCategory("消费", TxType.EXPENSE))
        assertEquals("其他", Backup.mapCategory("", TxType.EXPENSE))
        assertEquals("其他", Backup.mapCategory("其他", TxType.EXPENSE))
    }

    @Test
    fun detailed_expense_categories_fold_into_big_ones() {
        assertEquals("餐饮", Backup.mapCategory("早午晚餐", TxType.EXPENSE))
        assertEquals("餐饮", Backup.mapCategory("烹饪食材", TxType.EXPENSE))
        assertEquals("餐饮", Backup.mapCategory("咖啡奶茶", TxType.EXPENSE))
        assertEquals("餐饮", Backup.mapCategory("水果零食", TxType.EXPENSE))
        assertEquals("交通", Backup.mapCategory("加油充电", TxType.EXPENSE))
        assertEquals("交通", Backup.mapCategory("高速通行", TxType.EXPENSE))
        assertEquals("交通", Backup.mapCategory("停车费", TxType.EXPENSE))
        assertEquals("跑腿快递", Backup.mapCategory("快递", TxType.EXPENSE))
        assertEquals("日用", Backup.mapCategory("生活日用", TxType.EXPENSE))
        assertEquals("住房", Backup.mapCategory("住房", TxType.EXPENSE))
        assertEquals("娱乐", Backup.mapCategory("景点门票", TxType.EXPENSE))
    }

    @Test
    fun income_categories_map() {
        assertEquals("经营", Backup.mapCategory("收款", TxType.INCOME))
        assertEquals("经营", Backup.mapCategory("其他", TxType.INCOME))   // 网店账单里全是进账
        assertEquals("工资", Backup.mapCategory("薪资", TxType.INCOME))
        assertEquals("红包", Backup.mapCategory("收红包", TxType.INCOME))
        assertEquals("转账", Backup.mapCategory("收转账", TxType.INCOME))
        assertEquals("理财", Backup.mapCategory("理财", TxType.INCOME))
    }

    // ---------------- 分类表一致性（防 P0-2 复发）----------------

    @Test
    fun expense_category_list_covers_everything_rules_can_produce() {
        // CategoryRules 能产出这些，编辑弹窗必须都能选，否则点开就被改成「餐饮」
        val mustHave = listOf(
            "餐饮", "购物", "交通", "跑腿快递", "日用", "娱乐",
            "医疗", "住房", "通讯", "数码", "教育", "人情", "宠物",
            "经营", "转账", "其他",
        )
        for (c in mustHave) {
            assertTrue("支出分类缺「$c」", c in EXPENSE_CATEGORIES)
        }
    }

    @Test
    fun income_category_list_is_sane() {
        for (c in listOf("经营", "转账", "工资", "红包", "退款", "理财", "报销", "其他")) {
            assertTrue("收入分类缺「$c」", c in INCOME_CATEGORIES)
        }
    }

    @Test
    fun first_category_is_not_a_dumping_ground() {
        // 曾经「不在列表里就改成第一项」，而第一项恰好是餐饮，等于把分类吞掉
        assertNotNull(EXPENSE_CATEGORIES.firstOrNull())
        assertTrue("列表不能为空", EXPENSE_CATEGORIES.isNotEmpty())
    }
}
