package com.qinkun.accountbook.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * 付款后提醒补录。
 *
 * 为什么需要：支付宝/微信的「网页跳转支付」「扫码收款」根本不发系统通知，
 * 通知监听这条路走不通。但有一件事一定会发生 —— 用户**打开过**支付 App。
 *
 * 所以换个思路：不监听通知，监听「你刚离开支付宝/微信」这个动作。
 * 如果那前后没记上账，就提醒你补一笔（1 秒完成）。
 *
 * 用的权限是「使用情况访问」，只能读到"哪个 App 在前台"，
 * **读不到任何屏幕内容**，比无障碍服务安全得多。
 *
 * 省电：只在屏幕亮着时检查，45 秒一次，每次只查最近 3 分钟的事件。
 */
object PaymentWatcher {

    /** 会花钱/收钱的两个 App */
    val PAY_PACKAGES = listOf(
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.tencent.mm",               // 微信
    )

    private const val PREFS = "accountbook_prefs"
    private const val KEY_ENABLED = "payment_prompt_enabled"
    private const val KEY_LAST_PROMPT = "payment_last_prompt_at"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastPromptAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_PROMPT, 0L)

    fun markPrompted(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_PROMPT, at).apply()
    }

    /** 有没有「使用情况访问」权限 */
    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 判断「是不是刚离开支付 App」。
     *
     * 逻辑：翻最近 [windowMs] 内的事件，只看这两个 App。
     * 最后一条是「退到后台」→ 说明刚离开；最后一条是「回到前台」→ 说明还在用，不算。
     *
     * @return (包名, 离开时刻) 或 null
     */
    fun detectJustLeft(
        ctx: Context,
        windowMs: Long = 3 * 60 * 1000L,
        thresholdMs: Long = 90 * 1000L,
    ): Pair<String, Long>? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - windowMs, now) }.getOrNull() ?: return null

        val e = UsageEvents.Event()
        var leftPkg: String? = null
        var leftAt = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg !in PAY_PACKAGES) continue

            @Suppress("DEPRECATION")
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (e.timeStamp > leftAt) {
                        leftAt = e.timeStamp
                        leftPkg = pkg
                    }
                }
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // 又回到支付 App 了 —— 之前的"离开"作废
                    leftPkg = null
                    leftAt = 0L
                }
            }
        }

        if (leftPkg == null) return null
        if (now - leftAt > thresholdMs) return null
        return leftPkg to leftAt
    }

    fun appLabel(pkg: String): String = when (pkg) {
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.tencent.mm" -> "微信"
        else -> "支付"
    }
}
