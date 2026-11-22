package com.qinkun.accountbook.data

import kotlin.math.abs

/**
 * 「是不是同一笔」的判定（纯逻辑，方便单测）。
 *
 * 同一笔钱可能被抓到两次：
 *   · 同一个 App 的两条通道：微信付款页（无障碍）+ 微信支付凭证（通知）
 *   · **跨 App**（v4.2）：在淘宝闪购下单 → 淘宝的支付成功页（无障碍）记一次，
 *     支付宝「交易提醒」（通知）又记一次。来源 App 不同，旧逻辑按来源查重，必然记两笔。
 */
object TwinRules {

    /** 双通道合并窗口：±30 秒 */
    const val TWIN_WINDOW_MS = 30_000L

    /** 同一时刻（±2 秒）+ 同金额 → 必然是重复抓取，直接合并 */
    const val SAME_MOMENT_MS = 2_000L

    /** 两条都没有商户名时，只有「几乎同时」才敢判成同一笔 */
    const val NEAR_SIMULTANEOUS_MS = 8_000L

    /** 纯支付通道 */
    val PAY_CHANNELS = setOf("支付宝", "微信", "云闪付")

    /** 有真实商户名（不是空、不是「手动记账」、不是「XX（未识别商户）」） */
    fun isRealMerchant(m: String): Boolean =
        m.isNotBlank() && m != "手动记账" && !m.contains("未识别") && m !in PAY_CHANNELS

    private fun sameMerchantFamily(a: String, b: String): Boolean {
        if (!isRealMerchant(a) || !isRealMerchant(b)) return true
        return a.contains(b) || b.contains(a)
    }

    /** 一边是支付通道、另一边是下单平台（淘宝 + 支付宝、美团 + 微信） */
    fun isCrossChannel(a: Transaction, b: Transaction): Boolean =
        a.sourceApp != b.sourceApp &&
            (a.sourceApp in PAY_CHANNELS) != (b.sourceApp in PAY_CHANNELS) &&
            a.sourceApp != "手动" && b.sourceApp != "手动"

    fun isTwin(old: Transaction, tx: Transaction): Boolean {
        if (old.amountCents != tx.amountCents) return false
        // 收支方向不同绝不是同一笔：2 秒内「付 100」和「退 100」不能合并
        if (old.type != tx.type) return false
        val gap = abs(old.occurredAt - tx.occurredAt)
        if (gap > TWIN_WINDOW_MS) return false

        if (old.sourceApp == tx.sourceApp) {
            return when {
                gap <= SAME_MOMENT_MS -> true
                // 时间稍远：只有两边商户名对得上才算同一笔（菜市场连买两把 5 元的菜要记两笔）
                isRealMerchant(old.merchant) && isRealMerchant(tx.merchant) ->
                    sameMerchantFamily(old.merchant, tx.merchant)
                else -> gap <= NEAR_SIMULTANEOUS_MS
            }
        }
        // 跨 App：只认「通道 + 平台」这一种组合，金额一致、30 秒内即同一笔
        return isCrossChannel(old, tx)
    }

    /** 合并：信息取更全的那份，来源记成下单平台（淘宝/美团），备注里注明走的哪个通道 */
    fun merge(old: Transaction, tx: Transaction): Transaction {
        val cross = isCrossChannel(old, tx)
        val platform = if (cross) (if (old.sourceApp in PAY_CHANNELS) tx else old) else old
        val channel = if (cross) (if (old.sourceApp in PAY_CHANNELS) old else tx).sourceApp else null

        val betterMerchant = isRealMerchant(tx.merchant) && !isRealMerchant(old.merchant)
        val merchant = when {
            betterMerchant -> tx.merchant
            !isRealMerchant(old.merchant) && cross -> platform.merchant
            else -> old.merchant
        }
        val category = if (betterMerchant) tx.category else old.category
        val note = listOf(old.note, tx.note, channel?.let { "经$it" } ?: "")
            .filter { it.isNotBlank() }.distinct().joinToString(" · ").take(80)
        val raw = listOf(old.rawText, tx.rawText).filter { it.isNotBlank() }.distinct()
            .joinToString("\n").take(1000)
        return old.copy(
            merchant = merchant,
            category = category,
            sourceApp = platform.sourceApp,
            note = note,
            rawText = raw,
        )
    }
}
