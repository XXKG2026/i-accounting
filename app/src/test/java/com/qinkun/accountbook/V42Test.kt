package com.qinkun.accountbook

import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.data.TwinRules
import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.service.ScreenPageParser
import com.qinkun.accountbook.service.ScreenPageParser.Outcome
import com.qinkun.accountbook.service.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.2：付款结果页（扫码付款 / 美团 / 淘宝闪购）+ 跨 App 去重 */
class V42Test {

    private val WX = "com.tencent.mm"
    private val ALI = "com.eg.android.AlipayGphone"
    private val TB = "com.taobao.taobao"
    private val MT = "com.sankuai.meituan"
    private val MTW = "com.sankuai.meituan.takeoutnew"

    private fun ok(pkg: String, vararg nodes: String): TransactionParser.Parsed {
        val o = ScreenPageParser.analyze(pkg, nodes.toList())
        assertTrue("应当记账，实际：$o", o is Outcome.Ok)
        return (o as Outcome.Ok).parsed
    }

    private fun rejected(pkg: String, vararg nodes: String): String {
        val o = ScreenPageParser.analyze(pkg, nodes.toList())
        assertTrue("应当拒绝并留日志，实际：$o", o is Outcome.Rejected)
        return (o as Outcome.Rejected).reason
    }

    // ---------- 旧版漏记的根因 ----------

    /** 真实页面有二三十个节点、两三百字：旧版被「超过 120 字」那道闸丢掉 */
    @Test
    fun long_wechat_page_is_recorded() {
        val r = ok(
            WX,
            "微信支付", "支付成功", "瑞幸咖啡(软件园店)", "￥14.90",
            "支付方式", "零钱", "交易单号", "4200002345202611220012345678",
            "支付时间", "2026-11-22 11:20:31", "商户单号", "LK20261122112031000123",
            "关注公众号", "瑞幸咖啡", "领取优惠券 下次立减5元", "去使用",
            "完成", "有疑问？联系商户客服", "推荐服务", "微信支付分 先享后付",
        )
        assertEquals(1490L, r.amountCents)
        assertEquals("瑞幸咖啡(软件园店)", r.merchant)
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals("微信", r.sourceApp)
        assertTrue(r.note.contains("零钱"))
        assertTrue(r.rawText.length > 120)
    }

    /** 微信付款页带「关注公众号」：旧版命中黑名单整页丢弃 */
    @Test
    fun follow_official_account_checkbox_not_blocking() {
        val r = ok(WX, "支付成功", "老王面馆", "¥28.00", "关注公众号", "完成")
        assertEquals(2800L, r.amountCents)
    }

    @Test
    fun split_currency_nodes_merged() {
        val r = ok(WX, "支付成功", "老王面馆", "¥", "28.00", "完成")
        assertEquals(2800L, r.amountCents)
    }

    @Test
    fun alipay_scan_merchant_before_anchor() {
        val r = ok(ALI, "天外山", "付款成功", "¥71.00", "付款方式", "账户余额", "完成")
        assertEquals(7100L, r.amountCents)
        assertEquals("天外山", r.merchant)
        assertTrue(r.note.contains("扫码"))
    }

    @Test
    fun discount_amount_not_taken() {
        val r = ok(ALI, "支付成功", "全家便利店", "优惠", "¥2.00", "¥21.50", "完成")
        assertEquals(2150L, r.amountCents)
    }

    @Test
    fun paid_label_wins_over_original_price() {
        val r = ok(TB, "支付成功", "原价 ¥59.00", "实付款 ¥39.90", "查看订单", "继续逛逛")
        assertEquals(3990L, r.amountCents)
    }

    // ---------- 美团 / 淘宝闪购 ----------

    @Test
    fun taobao_flash_buy_recorded() {
        val r = ok(TB, "淘宝闪购", "支付成功", "¥32.50", "花呗", "查看订单", "返回首页")
        assertEquals(3250L, r.amountCents)
        assertEquals("淘宝", r.sourceApp)
        assertTrue(r.note.startsWith("淘宝闪购"))
        assertTrue(r.note.contains("花呗"))
    }

    @Test
    fun meituan_takeout_recorded_as_food() {
        val r = ok(MTW, "支付成功", "张亮麻辣烫(高新店)", "¥26.80", "查看订单", "完成")
        assertEquals(2680L, r.amountCents)
        assertEquals("张亮麻辣烫(高新店)", r.merchant)
        assertEquals("餐饮", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun meituan_order_list_rejected() {
        val reason = rejected(MT, "全部订单", "张亮麻辣烫", "已完成", "支付成功", "¥26.80", "再来一单")
        assertTrue(reason.contains("订单"))
    }

    @Test
    fun taobao_order_list_with_paid_not_anchor() {
        // 购物 App 里「已支付」不算锚点（订单列表里一排都是）
        assertTrue(ScreenPageParser.analyze(TB, listOf("已支付", "¥39.90", "查看详情")) is Outcome.NotResultPage)
    }

    // ---------- 该拦的 ----------

    @Test
    fun bill_list_rejected() {
        rejected(ALI, "全部账单", "11月22日", "支付成功", "¥15.00", "完成")
    }

    @Test
    fun chat_rejected() {
        rejected(WX, "张三", "支付成功了吗", "发消息", "完成")
    }

    @Test
    fun article_without_strong_button_rejected() {
        rejected(WX, "公众号", "支付成功后如何查看订单号", "¥20")
    }

    @Test
    fun no_amount_logged() {
        assertEquals("结果页上没找到金额", rejected(WX, "支付成功", "老王面馆", "完成"))
    }

    @Test
    fun unrelated_page_silent() {
        assertTrue(ScreenPageParser.analyze(WX, listOf("通讯录", "发现", "我")) is Outcome.NotResultPage)
        assertTrue(ScreenPageParser.analyze("com.example", listOf("支付成功", "¥1.00", "完成")) is Outcome.NotResultPage)
    }

    @Test
    fun raw_text_drops_ads() {
        val r = ok(WX, "支付成功", "老王面馆", "¥28.00", "领取优惠券", "完成")
        assertFalse(r.rawText.contains("领取"))
        assertTrue(r.rawText.contains("老王面馆"))
    }

    // ---------- 配置一致性 ----------

    @Test
    fun accessibility_config_matches_parser() {
        val f = listOf(
            File("src/main/res/xml/accessibility_service_config.xml"),
            File("app/src/main/res/xml/accessibility_service_config.xml"),
        ).first { it.exists() }
        val pkgs = Regex("""packageNames="([^"]+)"""").find(f.readText())!!.groupValues[1].split(",").map { it.trim() }.toSet()
        assertEquals(ScreenPageParser.SCREEN_APPS.keys, pkgs)
        // 页面上的来源名必须和通知那边一致，统计才不会把「美团」拆成两个
        ScreenPageParser.SCREEN_APPS.forEach { (pkg, name) -> assertEquals(name, TransactionParser.APP_NAMES[pkg]) }
    }

    // ---------- 跨 App 去重 ----------

    private fun tx(app: String, merchant: String, at: Long, cents: Long = 3250, note: String = "", raw: String = "") =
        Transaction(amountCents = cents, type = TxType.EXPENSE, category = "其他", merchant = merchant, sourceApp = app, occurredAt = at, note = note, rawText = raw)

    @Test
    fun taobao_page_and_alipay_notice_are_one() {
        val notice = tx("支付宝", "支付宝（未识别商户）", 1_000_000L, raw = "交易提醒 你有一笔32.50元的支出")
        val page = tx("淘宝", "淘宝闪购", 1_009_000L, note = "淘宝闪购 · 花呗", raw = "[淘宝 付款页] 支付成功 / ¥32.50")
        assertTrue(TwinRules.isTwin(notice, page))
        val m = TwinRules.merge(notice, page)
        assertEquals("淘宝", m.sourceApp)
        assertEquals("淘宝闪购", m.merchant)
        assertTrue(m.note.contains("经支付宝"))
        assertTrue(m.rawText.contains("交易提醒") && m.rawText.contains("付款页"))
    }

    @Test
    fun two_platforms_same_amount_not_merged() {
        // 美团和淘宝各买一单 32.5：都不是支付通道，不能合并
        assertFalse(TwinRules.isTwin(tx("美团", "A", 0), tx("淘宝", "B", 5_000)))
    }

    @Test
    fun cross_channel_outside_window_not_merged() {
        assertFalse(TwinRules.isTwin(tx("支付宝", "x", 0), tx("淘宝", "y", 40_000)))
    }

    @Test
    fun same_app_rules_kept() {
        assertTrue(TwinRules.isTwin(tx("微信", "老王面馆", 0), tx("微信", "", 1_000)))
        // 菜市场连买两把同价的菜，商户不同 → 两笔
        assertFalse(TwinRules.isTwin(tx("微信", "张记菜摊", 0), tx("微信", "李记菜摊", 15_000)))
        assertFalse(TwinRules.isTwin(tx("微信", "老王", 0), tx("微信", "老王", 0).copy(type = TxType.INCOME)))
    }

    @Test
    fun manual_never_merged_cross() {
        assertFalse(TwinRules.isTwin(tx("手动", "手动记账", 0), tx("淘宝", "淘宝闪购", 3_000)))
    }
}
