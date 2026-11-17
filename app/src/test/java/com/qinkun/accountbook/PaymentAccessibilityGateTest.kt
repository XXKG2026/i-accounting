package com.qinkun.accountbook

import com.qinkun.accountbook.service.PaymentAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍通道的三道闸：什么页面算「刚付完款」。
 *
 * 这个测试的价值全在**反例**上 —— 少记一笔是遗憾，把公众号文章记成支出是灾难。
 */
class PaymentAccessibilityGateTest {

    private fun page(vararg nodes: String) = nodes.toList()

    // ---------- 应该通过 ----------

    @Test
    fun wechat_scan_result_page_passes() {
        assertTrue(
            PaymentAccessibilityService.isPaymentResultPage(
                page("微信支付", "支付成功", "瑞幸咖啡(软件园店)", "￥14.90", "支付方式", "零钱", "完成")
            )
        )
    }

    @Test
    fun alipay_scan_result_page_passes() {
        assertTrue(
            PaymentAccessibilityService.isPaymentResultPage(
                page("支付宝", "付款成功", "全家便利店", "23.50元", "完成")
            )
        )
    }

    @Test
    fun result_page_with_order_number_passes() {
        assertTrue(
            PaymentAccessibilityService.isPaymentResultPage(
                page("交易成功", "老王面馆", "¥12.00", "订单号 20261117001")
            )
        )
    }

    // ---------- 应该拦掉 ----------

    @Test
    fun article_mentioning_success_is_rejected() {
        // 公众号文章里写了「支付成功后如何退款 ¥20」，没有结果页特征
        assertFalse(
            PaymentAccessibilityService.isPaymentResultPage(
                page("为什么支付成功后没到账", "举个例子：支付成功 ¥20 却没发货")
            )
        )
    }

    @Test
    fun bill_list_is_rejected() {
        assertFalse(
            PaymentAccessibilityService.isPaymentResultPage(
                page("全部账单", "11月17日 已支付 ¥15.00 完成")
            )
        )
    }

    @Test
    fun chat_page_is_rejected() {
        assertFalse(
            PaymentAccessibilityService.isPaymentResultPage(
                page("张三 已支付", "完成了吗", "发消息")
            )
        )
    }

    @Test
    fun empty_page_is_rejected() {
        assertFalse(PaymentAccessibilityService.isPaymentResultPage(emptyList()))
    }

    @Test
    fun anchor_without_result_hint_is_rejected() {
        // 只有锚点、没有「完成/订单号/支付方式」这类特征
        assertFalse(
            PaymentAccessibilityService.isPaymentResultPage(
                page("支付成功", "¥30.00")
            )
        )
    }

    @Test
    fun result_hint_without_anchor_is_rejected() {
        // 只有「完成」这种泛词（到处都有），没有成功锚点
        assertFalse(
            PaymentAccessibilityService.isPaymentResultPage(
                page("任务列表", "点击完成", "奖励 5 元")
            )
        )
    }
}
