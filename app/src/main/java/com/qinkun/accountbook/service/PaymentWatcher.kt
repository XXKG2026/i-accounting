package com.qinkun.accountbook.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 付款后提醒补录（v2.5 起「安静模式」）。
 *
 * 为什么需要：支付宝/微信的「网页跳转支付」「扫码收款」根本不发系统通知，
 * 通知监听这条路走不通。但有一件事一定会发生 —— 用户**打开过**支付 App。
 *
 * v2.5 收紧了三处（原来太敏感，查个余额也会提醒）：
 *   1. **停留时长门槛**：累计在前台不足 [MIN_FOREGROUND_MS] 的一律不算。
 *      查余额 3~5 秒；真付款要一路点下去（扫一扫 → 输入金额 → 密码 → 成功页），十几秒以上。
 *   2. **不弹系统通知**：只往首页放一张待确认卡片，用户打开 App 才看到 —— 不亮屏、不震动。
 *   3. **按包名分别计时**：3 分钟内先开微信又开支付宝，两边的停留时间互不污染。
 *
 * 用的权限是「使用情况访问」，只能读到"哪个 App 在前台"，
 * **读不到任何屏幕内容**，比无障碍服务安全得多。
 *
 * 省电：只在屏幕亮着时检查，45 秒一次，每次只查最近 8 分钟的事件。
 */
object PaymentWatcher {

    /** 会花钱/收钱的两个 App */
    val PAY_PACKAGES = listOf(
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.tencent.mm",               // 微信
    )

    /** 累计在前台超过这么久，才认为「真的操作过」——查余额达不到 */
    const val MIN_FOREGROUND_MS = 12_000L

    /**
     * 检测窗口。放得比轮询间隔宽很多，有两个原因：
     *   - 息屏时轮询降到 5 分钟一次，窗口太窄会把事件滑出去
     *   - 窗口内的事件要能凑够 [MIN_FOREGROUND_MS]
     */
    private const val WINDOW_MS = 8 * 60 * 1000L

    /** 离开后多久之内还算「刚才」——覆盖息屏 5 分钟的延迟 */
    private const val LEAVE_FRESH_MS = 5 * 60 * 1000L

    /** 待确认卡片的有效期：超过就自动失效，免得昨天的提示今天还挂着 */
    const val PENDING_TTL_MS = 30 * 60 * 1000L

    private const val PREFS = "accountbook_prefs"
    private const val KEY_ENABLED = "payment_prompt_enabled"
    private const val KEY_PENDING_AT = "payment_pending_at"
    private const val KEY_PENDING_APP = "payment_pending_app"
    private const val KEY_PENDING_PKG = "payment_pending_pkg"
    private const val KEY_HANDLED_LEAVE = "payment_handled_leave_at"

    /** 去重时间按包名分开存 —— 共用一个会让先离开的那个被后离开的盖掉 */
    private fun handledKey(pkg: String) = KEY_HANDLED_LEAVE + "_" + pkg

    /** 首页要显示的待确认项（app 是中文名，pkg 留着以后精确判断用） */
    data class Pending(val at: Long, val app: String, val pkg: String)

    /** 一次命中：包名 / 离开时刻 / 累计前台时长 */
    data class Hit(val pkg: String, val leaveAt: Long, val foregroundMs: Long)

    /** 一段「在前台」的时间区间 */
    data class FgSpan(val pkg: String, val start: Long, val end: Long)

    /**
     * 纯逻辑：从若干段前台区间里挑出「该不该提醒、提醒哪个」。
     *
     * 抽出来的原因：这块判断有 4 个条件相互作用（还在前台 / 时长门槛 / 新鲜度 / 已处理），
     * 埋在 UsageStats 的 while 循环里根本没法单测。
     *
     * @param spans     窗口内的前台区间
     * @param openNow   此刻仍在前台的包名（正在用，不打扰）
     * @param now       当前时刻
     * @param handled   上一次已经处理过的离开时刻
     */
    internal fun pickHit(
        spans: List<FgSpan>,
        openNow: Set<String>,
        now: Long,
        handled: (String) -> Long,
    ): Hit? = PAY_PACKAGES
        .filter { it !in openNow }
        .mapNotNull { pkg ->
            val ofPkg = spans.filter { it.pkg == pkg }
            val leave = ofPkg.maxOfOrNull { it.end } ?: return@mapNotNull null
            val fg = ofPkg.sumOf { (it.end - it.start).coerceAtLeast(0L) }
            if (leave <= handled(pkg)) return@mapNotNull null     // 已经处理过
            if (now - leave > LEAVE_FRESH_MS) return@mapNotNull null
            if (fg < MIN_FOREGROUND_MS) return@mapNotNull null     // 只是看了一眼
            Hit(pkg, leave, fg)
        }
        .maxByOrNull { it.leaveAt }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clearPending(ctx)
    }

    // ---------------- 待确认状态 ----------------

    /** App 启动时调一次：把上次残留的待确认项捞回来（过期的不捞） */
    fun restorePending(ctx: Context) {
        val at = prefs(ctx).getLong(KEY_PENDING_AT, 0L)
        val app = prefs(ctx).getString(KEY_PENDING_APP, null)
        val pkg = prefs(ctx).getString(KEY_PENDING_PKG, null)
        _pending.value = if (isFresh(at) && app != null && pkg != null) {
            Pending(at, app, pkg)
        } else null
    }

    private fun isFresh(at: Long) =
        at > 0L && System.currentTimeMillis() - at < PENDING_TTL_MS

    /** 卡片是否还有效（UI 每次重组都问一次，过期就自己隐藏） */
    fun isPendingFresh(p: Pending): Boolean = isFresh(p.at)

    fun setPending(ctx: Context, pkg: String) {
        val p = Pending(System.currentTimeMillis(), appLabel(pkg), pkg)
        prefs(ctx).edit()
            .putLong(KEY_PENDING_AT, p.at)
            .putString(KEY_PENDING_APP, p.app)
            .putString(KEY_PENDING_PKG, p.pkg)
            .apply()
        _pending.value = p
    }

    fun clearPending(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_PENDING_AT)
            .remove(KEY_PENDING_APP)
            .remove(KEY_PENDING_PKG)
            .apply()
        _pending.value = null
    }

    /**
     * 记录「这次离开已经处理过了」。
     *
     * 为什么必须要：轮询 45 秒一次、窗口 8 分钟。用户点了「没有」清掉卡片后，
     * 下一次轮询还会在同一批事件里再认出「刚离开」，卡片又冒出来 —— 无限复活。
     */
    private fun handledLeaveAt(ctx: Context, pkg: String): Long =
        prefs(ctx).getLong(handledKey(pkg), 0L)

    fun markHandled(ctx: Context, pkg: String, leaveAt: Long) {
        prefs(ctx).edit().putLong(handledKey(pkg), leaveAt).apply()
    }

    // ---------------- 权限 ----------------

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
     * 判断「刚才是不是真的用了一次支付 App，然后离开了」。
     *
     * 三个必须同时满足：
     *   1. 该 App 现在**不在**前台（还在里面就当正在付款，不打扰）
     *   2. 累计前台时长 ≥ [MIN_FOREGROUND_MS]（挡掉"打开看一眼"）
     *   3. 离开时刻在 [LEAVE_FRESH_MS] 之内，且已经处理过的不重复报
     *
     * 计时按包名分开记：Activity 之间切换会 PAUSED→RESUMED，
     * 所以时长必须**累加**，不能取"最后一次进入→离开"那一段。
     */
    fun detectJustLeft(ctx: Context): Hit? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        // 多查 2 分钟：如果用户是在窗口左边界之前进入前台的，
        // 只查 WINDOW 内的部分会只剩一个 PAUSED、没有 RESUMED，那段停留就白丢了
        val events = runCatching {
            usm.queryEvents(now - WINDOW_MS - 120_000L, now)
        }.getOrNull() ?: return null

        val fgSince = HashMap<String, Long>()   // 该包名本次进入前台的时刻
        val spans = ArrayList<FgSpan>()

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg !in PAY_PACKAGES) continue

            @Suppress("DEPRECATION")
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (!fgSince.containsKey(pkg)) fgSince[pkg] = e.timeStamp
                }

                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val since = fgSince.remove(pkg)
                    if (since != null && e.timeStamp > since) {
                        spans.add(FgSpan(pkg, since, e.timeStamp))
                    }
                }
            }
        }

        return pickHit(spans, fgSince.keys.toSet(), now) { pkg -> handledLeaveAt(ctx, pkg) }
    }

    fun appLabel(pkg: String): String = when (pkg) {
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.tencent.mm" -> "微信"
        else -> "支付"
    }
}
