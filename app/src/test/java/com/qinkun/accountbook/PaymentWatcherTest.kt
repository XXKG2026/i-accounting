package com.qinkun.accountbook

import com.qinkun.accountbook.service.PaymentWatcher
import com.qinkun.accountbook.service.PaymentWatcher.FgSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 付款后提醒的判定逻辑。
 *
 * 这块唯一的目的是**别乱提醒** —— 所以测试重点全在「什么情况下不该提醒」。
 * 所有时间都是毫秒时间戳，用固定基准值方便读。
 */
class PaymentWatcherTest {

    private val alipay = "com.eg.android.AlipayGphone"
    private val wechat = "com.tencent.mm"
    private val now = 1_000_000_000_000L

    private fun span(pkg: String, agoStartMs: Long, agoEndMs: Long) =
        FgSpan(pkg, now - agoStartMs, now - agoEndMs)

    // ---------- 该提醒的 ----------

    @Test
    fun real_payment_leaving_is_detected() {
        // 进去了 30 秒，10 秒前离开
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 40_000, 10_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNotNull(hit)
        assertEquals(alipay, hit!!.pkg)
        assertEquals(30_000L, hit.foregroundMs)
    }

    @Test
    fun activity_switches_accumulate() {
        // 支付宝内翻了两个页面：8 秒 + 8 秒 = 16 秒，超过 12 秒门槛
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 60_000, 52_000), span(alipay, 51_000, 43_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNotNull(hit)
        assertEquals(16_000L, hit!!.foregroundMs)
    }

    // ---------- 不该提醒的 ----------

    @Test
    fun quick_balance_check_is_ignored() {
        // 只看了一眼：5 秒
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 15_000, 10_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNull(hit)
    }

    @Test
    fun still_inside_app_is_ignored() {
        // 还在支付宝里（可能正在付款），别打扰
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 30_000, 20_000)),
            openNow = setOf(alipay),
            now = now,
            handled = { 0L },
        )
        assertNull(hit)
    }

    @Test
    fun already_handled_is_ignored() {
        // 用户点过「没有」或「记一笔」了，同一次离开不再复活
        val leaveAt = now - 10_000
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 40_000, 10_000)),
            openNow = emptySet(),
            now = now,
            handled = { leaveAt },
        )
        assertNull(hit)
    }

    @Test
    fun stale_leave_is_ignored() {
        // 6 分钟前就离开了（超过 5 分钟新鲜期）
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 400_000, 360_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNull(hit)
    }

    @Test
    fun two_apps_are_counted_separately() {
        // 微信 8 秒 + 支付宝 8 秒：总量 16 秒，但任何一边都没到 12 秒门槛
        val hit = PaymentWatcher.pickHit(
            listOf(span(wechat, 60_000, 52_000), span(alipay, 50_000, 42_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNull(hit)
    }

    @Test
    fun picks_the_more_recent_leave() {
        // 两个都够久，取更近离开的那个
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 120_000, 100_000), span(wechat, 40_000, 5_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNotNull(hit)
        assertEquals(wechat, hit!!.pkg)
    }

    @Test
    fun empty_events_give_nothing() {
        assertNull(PaymentWatcher.pickHit(emptyList(), emptySet(), now) { 0L })
    }

    @Test
    fun handled_is_per_package() {
        // 微信那次已经处理过，支付宝那次还没有 —— 支付宝应该照常提醒
        val wechatLeave = now - 30_000
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 60_000, 10_000), span(wechat, 80_000, 30_000)),
            openNow = emptySet(),
            now = now,
            handled = { pkg -> if (pkg == wechat) wechatLeave else 0L },
        )
        assertNotNull(hit)
        assertEquals(alipay, hit!!.pkg)
    }

    @Test
    fun boundary_partial_span_still_counts() {
        // 模拟「窗口只截到一段尾巴」：只有一段 15 秒的区间，也要能算数
        val hit = PaymentWatcher.pickHit(
            listOf(span(alipay, 20_000, 5_000)),
            openNow = emptySet(),
            now = now,
            handled = { 0L },
        )
        assertNotNull(hit)
        assertEquals(15_000L, hit!!.foregroundMs)
    }
}
