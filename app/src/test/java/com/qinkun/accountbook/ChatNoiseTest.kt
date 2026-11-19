package com.qinkun.accountbook

import com.qinkun.accountbook.data.TxType
import com.qinkun.accountbook.service.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 聊天消息不能被当成流水。
 *
 * 这是一次真实事故：微信里一条回复写着「又自动进了一笔 379.99 元的收入」，
 * 通知栏里出现了这段话，App 就记了一笔 379.99 的收入 —— 账本凭空多钱。
 *
 * 两道防线：
 *   1. 微信/支付宝的通知必须是「支付通知」（含支付凭证、收款到账这类真信号）
 *   2. 正文超过 300 字的一律不是支付通知（支付通知都很短）
 */
class ChatNoiseTest {

    private val wx = "com.tencent.mm"
    private val ali = "com.eg.android.AlipayGphone"

    // ---------- 该拦下的 ----------

    @Test
    fun chat_message_with_amount_is_ignored() {
        // 这句话是一字不差的事故原文
        val p = TransactionParser.parse(
            wx, "小H",
            listOf(
                "确认：- 2026 年：支出 160408.54 / 收入 324262.89 / 结余 163854.35",
                "刚才 15:00 又自动进了一笔 379.99 元的收入",
            ),
        )
        assertNull("聊天消息不能记成收入", p)
    }

    @Test
    fun short_chat_with_word_income_is_ignored() {
        // 短消息也得拦住 —— 光靠长度限制不够
        assertNull(
            TransactionParser.parse(wx, "阿乐", listOf("这个月收入 500 元吧")),
        )
    }

    @Test
    fun overly_long_text_is_ignored() {
        // 公众号 / 群公告 / 系统播报
        val long = "支付成功 " + "这是一段很长的说明文字".repeat(40) + " 12.34 元"
        assertNull(
            TransactionParser.parse(wx, "某公众号", listOf(long)),
        )
    }

    @Test
    fun alipay_long_message_is_ignored() {
        // 支付宝的推送/客服长文，靠「超过 300 字就不是支付通知」拦下。
        // 支付宝的标题不像微信那样能区分（都叫「支付宝」），所以这条靠长度兜底。
        assertNull(
            TransactionParser.parse(
                ali, "支付宝客服",
                listOf("您本月支出 2000 元，收入 3000 元。" + "详情请打开支付宝查看。".repeat(20)),
            ),
        )
    }

    @Test
    fun article_push_is_ignored() {
        assertNull(
            TransactionParser.parse(
                wx, "某订阅号",
                listOf("今日推荐", "这篇讲的是如何控制支出，比如每天消费不超过 50 元"),
            ),
        )
    }

    // ---------- 该放过的 ----------

    @Test
    fun wechat_pay_notice_still_works() {
        val p = TransactionParser.parse(
            wx, "微信支付",
            listOf("微信支付凭证", "支付成功", "¥50.00"),
        )
        assertNotNull("微信支付凭证必须能记账", p)
        assertEquals(TxType.EXPENSE, p!!.type)
        assertEquals(5000L, p.amountCents)
    }

    @Test
    fun wechat_receipt_notice_is_income() {
        val p = TransactionParser.parse(
            wx, "微信支付",
            listOf("收款到账", "¥198.00"),
        )
        assertNotNull("收款到账必须能记账", p)
        assertEquals(TxType.INCOME, p!!.type)
        assertEquals(19800L, p.amountCents)
    }

    @Test
    fun alipay_pay_notice_still_works() {
        val p = TransactionParser.parse(
            ali, "支付宝",
            listOf("支付成功", "付款金额 32.30 元", "美团"),
        )
        assertNotNull("支付宝支付成功必须能记账", p)
        assertEquals(TxType.EXPENSE, p!!.type)
        assertEquals(3230L, p.amountCents)
    }

    @Test
    fun alipay_receipt_notice_is_income() {
        val p = TransactionParser.parse(
            ali, "支付宝",
            listOf("你已成功收款198.00元.", "已转入余额"),
        )
        assertNotNull(p)
        assertEquals(TxType.INCOME, p!!.type)
        assertEquals(19800L, p.amountCents)
    }

    @Test
    fun normal_price_is_not_mistaken_for_big_amount() {
        // 50 元的支付通知不能被写成 5000 元
        val p = TransactionParser.parse(
            wx, "微信支付",
            listOf("微信支付凭证", "支付成功", "¥50.00"),
        )
        assertEquals(5000L, p!!.amountCents)
    }
}
