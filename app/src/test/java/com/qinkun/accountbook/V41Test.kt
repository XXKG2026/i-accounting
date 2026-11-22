package com.qinkun.accountbook

import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.service.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.1：支付宝收钱码收款「你已成功」商户名、顾客标签、备注、汇总金额 */
class V41Test {

    private val ALIPAY = "com.eg.android.AlipayGphone"

    private fun p(pkg: String, title: String, vararg texts: String) =
        TransactionParser.parse(pkg, title, texts.toList())

    @Test
    fun old_customer_receipt_merchant_is_tag() {
        val r = p(ALIPAY, "你已成功收款168.00元（老顾客消费）", "已转入余额", "线上收款支持花呗/信用卡>>")
        assertNotNull(r); r!!
        assertEquals(16800L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
        assertEquals("老顾客", r.merchant)
        assertEquals("老顾客消费", r.note)
        assertEquals("经营", TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type))
    }

    @Test
    fun new_customer_receipt_merchant_is_tag() {
        val r = p(ALIPAY, "支付宝", "你已成功收款25.00元（新顾客消费）")
        assertNotNull(r); r!!
        assertEquals("新顾客", r.merchant)
        assertEquals(TxType.INCOME, r.type)
    }

    @Test
    fun merchant_never_you_succeeded() {
        val r = p(ALIPAY, "你已成功收款198.00元.", "已转入余额 做生意收钱提现免费>>")
        assertNotNull(r); r!!
        assertFalse(r.merchant.contains("成功"))
        assertFalse(r.merchant.startsWith("你"))
    }

    @Test
    fun daily_summary_not_taken_as_amount() {
        val r = p(ALIPAY, "你已成功收款168.00元（老顾客消费）", "今日第3笔收款，共计500.00元")
        assertNotNull(r); r!!
        assertEquals(16800L, r.amountCents)
        assertEquals("老顾客消费，今日第3笔收款，共计500.00元", r.note)
    }

    @Test
    fun wechat_summary_not_taken_as_amount() {
        val r = p("com.tencent.mm", "微信支付", "微信支付收款到账 ¥10.00", "今日第2笔收款，共计¥30.00")
        assertNotNull(r); r!!
        assertEquals(1000L, r.amountCents)
        assertEquals(TxType.INCOME, r.type)
    }

    @Test
    fun note_drops_marketing() {
        val n = TransactionParser.extractNote("你已成功收款25.00元（新顾客消费） 今日第1笔收款，共计25.00元 做生意收钱提现免费>>")
        assertEquals("新顾客消费，今日第1笔收款，共计25.00元", n)
    }

    @Test
    fun note_empty_for_plain_payment() {
        val r = p(ALIPAY, "支付宝", "你已成功付款12.50元")
        assertNotNull(r); r!!
        assertEquals("", r.note)
    }

    @Test
    fun receipt_tag() {
        assertEquals("老顾客", TransactionParser.receiptTag("收款168.00元（老顾客消费）"))
        assertNull(TransactionParser.receiptTag("你已成功收款88.00元"))
    }

    @Test
    fun named_title_still_wins() {
        // 标题本身是对方名字时照用，不被顾客标签覆盖
        val r = p("com.tencent.mm", "老王", "你已成功收款88.00元（老顾客消费）")
        assertNotNull(r); r!!
        assertEquals("老王", r.merchant)
    }

    @Test
    fun merge_fields_dedups_contained() {
        val m = TransactionParser.mergeFields(
            listOf("你已成功收款168.00元", "你已成功收款168.00元（老顾客消费）", "", "今日第3笔收款")
        )
        assertEquals(listOf("你已成功收款168.00元（老顾客消费）", "今日第3笔收款"), m)
    }

    @Test
    fun legit_body_merchant_kept() {
        val r = p(ALIPAY, "支付宝", "你在老王面馆消费35.00元")
        assertNotNull(r); r!!
        assertEquals("老王面馆", r.merchant)
        assertTrue(r.note.isEmpty())
    }
}
