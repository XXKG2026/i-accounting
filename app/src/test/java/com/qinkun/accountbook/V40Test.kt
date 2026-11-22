package com.qinkun.accountbook

import com.qinkun.accountbook.data.Period
import com.qinkun.accountbook.data.Repo
import com.qinkun.accountbook.data.StatsCalc
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.service.TransactionParser
import com.qinkun.accountbook.util.parseYuanInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** v4.0 修复与新功能的回归测试 */
class V40Test {

    private fun p(pkg: String, title: String, vararg texts: String) =
        TransactionParser.parse(pkg, title, texts.toList())

    private fun cat(r: TransactionParser.Parsed) =
        TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type)

    // ---------------- 扫码页商户名 ----------------

    @Test
    fun scan_merchant_skips_yuan_amount_before_name() {
        // 以前 「\\d」 写错，「23.50元」会被当成商户名
        val nodes = listOf("付款成功", "23.50元", "全家便利店", "完成")
        assertEquals("全家便利店", TransactionParser.extractMerchantFromScanNodes(nodes))
    }

    @Test
    fun scan_merchant_skips_pure_number() {
        val nodes = listOf("支付成功", "14.90", "瑞幸咖啡", "完成")
        assertEquals("瑞幸咖啡", TransactionParser.extractMerchantFromScanNodes(nodes))
    }

    // ---------------- 商家收款码 = 收入 ----------------

    @Test
    fun wechat_merchant_qr_receipt_is_income() {
        val r = p("com.tencent.mm", "微信支付", "微信支付收款0.01元(朋友到店)")
        assertNotNull(r); r!!
        assertEquals(1L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
        assertEquals("经营", cat(r))
    }

    @Test
    fun wechat_merchant_qr_receipt_larger_amount() {
        val r = p("com.tencent.mm", "微信支付", "微信支付收款128.00元")
        assertNotNull(r); r!!
        assertEquals(12800L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
    }

    @Test
    fun alipay_collect_code_is_income() {
        val r = p("com.eg.android.AlipayGphone", "支付宝", "收钱码收款 ¥36.00")
        assertNotNull(r); r!!
        assertEquals(3600L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
        assertEquals("经营", cat(r))
    }

    @Test
    fun wechat_payment_still_expense() {
        // 回归：付款凭证不能被新加的收入词带偏
        val r = p("com.tencent.mm", "微信支付", "微信支付凭证 支付成功 ¥50.00")
        assertNotNull(r); r!!
        assertEquals(TxType.EXPENSE, r.type)
    }

    // ---------------- 新增 App ----------------

    @Test
    fun new_sibling_apps_supported() {
        listOf(
            "com.tmall.wireless" to "天猫",
            "com.taobao.litetao" to "淘宝特价版",
            "com.sankuai.meituan.takeoutnew" to "美团外卖",
            "com.jd.jrapp" to "京东金融",
            "com.ss.android.ugc.aweme.lite" to "抖音极速版",
            "com.kuaishou.nebula" to "快手极速版",
            "com.taobao.qianniu" to "千牛",
            "com.xunmeng.merchant" to "拼多多商家版",
        ).forEach { (pkg, name) ->
            assertEquals(name, TransactionParser.APP_NAMES[pkg])
        }
    }

    @Test
    fun takeout_app_expense_is_food() {
        val r = p("com.sankuai.meituan.takeoutnew", "美团外卖", "支付成功，实付¥28.80")
        assertNotNull(r); r!!
        assertEquals(2880L, r.amountCents)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("餐饮", cat(r))
    }

    @Test
    fun tmall_expense_is_shopping() {
        val r = p("com.tmall.wireless", "天猫", "付款成功 实付¥199.00")
        assertNotNull(r); r!!
        assertEquals("购物", cat(r))
    }

    @Test
    fun qianniu_settlement_is_business_income() {
        val r = p("com.taobao.qianniu", "千牛", "买家已确认收货，货款已到账 ¥268.00")
        assertNotNull(r); r!!
        assertEquals(26800L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
        assertEquals("经营", cat(r))
    }

    @Test
    fun xianyu_sale_is_business() {
        val r = p("com.taobao.idlefish", "闲鱼", "你的商品已卖出，到账¥200.00")
        assertNotNull(r); r!!
        assertEquals("经营", cat(r))
    }

    @Test
    fun xhs_creator_commission_is_business() {
        val r = p("com.xingin.xhs", "小红书", "佣金到账 ¥45.60")
        assertNotNull(r); r!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals("经营", cat(r))
    }

    @Test
    fun fund_sale_is_wealth_not_business() {
        assertEquals(
            "理财",
            TransactionParser.guessCategory("支付宝", "支付宝", "基金卖出到账 1000.00 元", TxType.INCOME)
        )
    }

    @Test
    fun red_packet_from_contact_still_red_packet() {
        assertEquals(
            "红包",
            TransactionParser.guessCategory("微信", "宝贝", "收到红包 8.88 元", TxType.INCOME)
        )
    }

    // ---------------- 金额输入 ----------------

    @Test
    fun yuan_input_tolerant() {
        assertEquals(1299.0, parseYuanInput("1,299.00")!!, 0.0001)
        assertEquals(35.0, parseYuanInput("¥35")!!, 0.0001)
        assertEquals(12.5, parseYuanInput("１２．５")!!, 0.0001)
        assertNull(parseYuanInput("abc"))
        assertNull(parseYuanInput(""))
    }

    // ---------------- 统计 ----------------

    private fun tx(cents: Long, type: TxType, cat: String, app: String, merchant: String, at: Long) =
        Transaction(amountCents = cents, type = type, category = cat, sourceApp = app, merchant = merchant, occurredAt = at)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis

    private val sample = listOf(
        tx(3500, TxType.EXPENSE, "餐饮", "美团", "老王面馆", at(2026, 11, 2)),
        tx(1500, TxType.EXPENSE, "餐饮", "微信", "老王面馆", at(2026, 11, 3)),
        tx(19900, TxType.EXPENSE, "购物", "淘宝", "某旗舰店", at(2026, 11, 3)),
        tx(800, TxType.EXPENSE, "交通", "支付宝", "支付宝（未识别商户）", at(2026, 11, 20)),
        tx(50000, TxType.INCOME, "经营", "闲鱼", "闲鱼", at(2026, 11, 5)),
        tx(200, TxType.INCOME, "红包", "", "手动记账", at(2026, 11, 5)),
    )

    @Test
    fun group_by_category_sorted() {
        val g = StatsCalc.byCategory(sample, TxType.EXPENSE)
        assertEquals(listOf("购物", "餐饮", "交通"), g.map { it.key })
        assertEquals(5000L, g[1].total)
        assertEquals(2, g[1].count)
    }

    @Test
    fun group_by_source_manual_named() {
        val g = StatsCalc.bySource(sample, TxType.INCOME)
        assertEquals(listOf("闲鱼", "手动"), g.map { it.key })
    }

    @Test
    fun top_merchants_skip_unrecognized() {
        val g = StatsCalc.topMerchants(sample, TxType.EXPENSE)
        assertTrue(g.none { it.key.contains("未识别") })
        assertEquals("某旗舰店", g.first().key)
    }

    @Test
    fun trend_month_buckets() {
        val start = Repo.rangeFor(Period.MONTH, 0, at(2026, 11, 22)).first
        val t = StatsCalc.trend(Period.MONTH, start, sample)
        assertEquals(30, t.size)
        assertEquals(3500L, t[1].expense)
        assertEquals(21400L, t[2].expense)
        assertEquals(50200L, t[4].income)
    }

    @Test
    fun trend_year_buckets() {
        val start = Repo.rangeFor(Period.YEAR, 0, at(2026, 11, 22)).first
        val t = StatsCalc.trend(Period.YEAR, start, sample)
        assertEquals(12, t.size)
        assertEquals(25700L, t[10].expense)
    }

    @Test
    fun elapsed_days() {
        assertEquals(22, StatsCalc.elapsedDays(at(2026, 11, 1, 0), at(2026, 11, 22, 23)))
        assertEquals(1, StatsCalc.elapsedDays(at(2026, 11, 22, 0), at(2026, 11, 22, 23)))
        assertEquals(0, StatsCalc.elapsedDays(10L, 9L))
    }

    @Test
    fun detail_filter() {
        assertEquals(2, StatsCalc.filter(sample, "面馆").size)
        assertEquals(2, StatsCalc.filter(sample, "", TxType.INCOME).size)
        assertEquals(1, StatsCalc.filter(sample, "", null, "手动").size)
        assertEquals(1, StatsCalc.filter(sample, "199").size)
    }
}
