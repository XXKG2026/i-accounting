package com.qinkun.accountbook.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qinkun.accountbook.data.CaptureLog
import com.qinkun.accountbook.data.Repo
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.util.PiiMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 付款结果页自动记账（需要用户手动授权无障碍）。
 *
 * 覆盖：微信/支付宝扫码、付款码付款（这类付款**一条系统通知都不发**），
 * 以及美团、美团外卖、淘宝（含淘宝闪购）、天猫、饿了么、京东、拼多多的下单支付成功页。
 *
 * v4.2 重写，修掉了「扫码付款一直采集不到」的 4 个原因：
 *   1. 整页文字被喂进通知解析器，被「超过 120 字不是通知」那道闸丢掉 —— 改用 [ScreenPageParser] 按节点解析
 *   2. 微信付款成功页带「关注公众号」，命中黑名单整页丢弃 —— 改为软黑名单，有「完成」按钮时放行
 *   3. 节点只收到第 12 层、前 40 个 —— 微信/支付宝页面层级很深，金额和商户经常在 12 层以下
 *   4. 事件节流是「丢弃」：250ms 内的后续事件直接扔掉，偏偏页面最后一次渲染（金额出现）
 *      常常就在被扔掉的那次 —— 改为「防抖」：停下来 400ms 再读，且最长 1.2 秒必读一次
 *
 * 安全边界（这段是承诺，改代码时别破坏）：
 *   1. 配置里只订阅 [ScreenPageParser.SCREEN_APPS] 里的包名
 *   2. 页面必须有「支付成功 / 付款成功 / 交易成功 / 下单成功」**且**有结果页特征
 *   3. 命中账单页 / 订单列表 / 聊天页关键词一律跳过
 *   4. 只取文本，不模拟点击、不注入事件、不改别家 App 的任何东西
 *   5. 数据只进本地数据库，不联网
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: Repo
    private val main = Handler(Looper.getMainLooper())

    private var lastSignature: String = ""
    private var lastCaptureAt: Long = 0L
    private var lastRejectSig: String = ""
    private var lastRejectAt: Long = 0L

    /** 防抖状态 */
    private var pendingPkg: String? = null
    private var pendingSince: Long = 0L
    private val scanRunnable = Runnable { runScan() }

    override fun onCreate() {
        super.onCreate()
        repo = Repo(applicationContext)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 运行时再补一遍 flag：有些 ROM 不认 xml 里的部分 flag
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        }
        Log.i(TAG, "无障碍已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in ScreenPageParser.SCREEN_APPS) return

        val now = System.currentTimeMillis()
        if (pendingPkg != pkg) { pendingPkg = pkg; pendingSince = now }
        main.removeCallbacks(scanRunnable)
        // 防抖：页面停止变化 400ms 后读一次；一直在刷（动画、倒计时）也最多等 1.2 秒
        val waited = now - pendingSince
        if (waited >= MAX_WAIT_MS) {
            runScan()
        } else {
            main.postDelayed(scanRunnable, DEBOUNCE_MS)
        }
    }

    private fun runScan() {
        val pkg = pendingPkg ?: return
        pendingPkg = null
        val nodes = runCatching { collectWindowTexts(pkg) }.getOrElse {
            Log.e(TAG, "读页面失败", it); return
        }
        if (nodes.isEmpty()) return

        when (val out = ScreenPageParser.analyze(pkg, nodes)) {
            ScreenPageParser.Outcome.NotResultPage -> Unit
            is ScreenPageParser.Outcome.Rejected -> logRejected(pkg, out)
            is ScreenPageParser.Outcome.Ok -> save(pkg, out.parsed)
        }
    }

    /** 当前属于这个包的所有窗口的文本（收银台常是弹窗，不在 rootInActiveWindow 里） */
    private fun collectWindowTexts(pkg: String): List<String> {
        val out = ArrayList<String>(MAX_NODES)
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            windows?.forEach { w -> w.root?.takeIf { it.packageName?.toString() == pkg }?.let { roots.add(it) } }
        }
        if (roots.isEmpty()) rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.let { roots.add(it) }
        for (r in roots) collectNodeTexts(r, out, 0)
        return out
    }

    /** 递归收文本。深度放到 40、节点放到 160：微信/支付宝的结果页 12 层根本不够 */
    private fun collectNodeTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        if (!node.isVisibleToUser) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it.take(80)) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (out.lastOrNull() != it) out.add(it.take(80))
        }
        for (i in 0 until node.childCount) {
            runCatching { collectNodeTexts(node.getChild(i), out, depth + 1) }
        }
    }

    private fun logRejected(pkg: String, r: ScreenPageParser.Outcome.Rejected) {
        // 同一页反复重绘只记一条
        val now = System.currentTimeMillis()
        val sig = pkg + r.reason + r.rawText.take(80)
        if (sig == lastRejectSig && now - lastRejectAt < 60_000L) return
        lastRejectSig = sig; lastRejectAt = now
        val app = ScreenPageParser.SCREEN_APPS[pkg] ?: pkg
        scope.launch {
            runCatching {
                repo.addCaptureLog(
                    CaptureLog(
                        at = now, sourceApp = app,
                        title = "付款页（无障碍）",
                        text = PiiMasker.mask(r.rawText),
                        success = false,
                        reason = r.reason,
                    )
                )
            }
        }
        Log.d(TAG, "付款页未记账（${r.reason}）")
    }

    private fun save(pkg: String, parsed: TransactionParser.Parsed) {
        val now = System.currentTimeMillis()
        val sig = "$pkg:${parsed.amountCents}:${parsed.merchant}"
        // 20 秒：页面重绘的重复回调挡得住，而菜市场连买两摊同样的东西不会被吞
        if (sig == lastSignature && now - lastCaptureAt < 20_000L) return
        lastSignature = sig
        lastCaptureAt = now

        scope.launch {
            runCatching {
                val maskedMerchant = PiiMasker.mask(parsed.merchant)
                val category = repo.learnedCategory(maskedMerchant, parsed.type)
                    ?: TransactionParser.guessCategory(
                        parsed.sourceApp, parsed.merchant, parsed.rawText, parsed.type
                    )
                val id = repo.addIfNew(
                    Transaction(
                        amountCents = parsed.amountCents,
                        type = parsed.type,
                        category = category,
                        merchant = maskedMerchant,
                        sourceApp = parsed.sourceApp,
                        note = PiiMasker.mask(parsed.note),
                        occurredAt = now,
                        rawText = PiiMasker.mask(parsed.rawText),
                    )
                )
                val isDup = id < 0
                val yuan = String.format("%.2f", parsed.amountCents / 100.0)

                repo.addCaptureLog(
                    CaptureLog(
                        at = now, sourceApp = parsed.sourceApp,
                        title = "付款页（无障碍）",
                        text = PiiMasker.mask(parsed.rawText),
                        success = !isDup,
                        reason = if (isDup) "和通知抓到的是同一笔，已合并" else "支出 ¥$yuan · $category · ${parsed.merchant}",
                        amountCents = if (isDup) 0 else parsed.amountCents,
                    )
                )

                if (!isDup) {
                    // 记账成功 → 首页那张「好像有一笔没记上」就不需要了
                    PaymentWatcher.markHandled(applicationContext, pkg, now)
                    PaymentWatcher.clearPending(applicationContext)
                    Log.i(TAG, "无障碍记账: ${parsed.sourceApp} ¥$yuan ${parsed.merchant} → $category")
                }
            }.onFailure { Log.e(TAG, "无障碍入库失败", it) }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(scanRunnable)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PayAccessibility"
        private const val DEBOUNCE_MS = 400L
        private const val MAX_WAIT_MS = 1_200L
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 160

        /** 兼容旧测试：只看微信/支付宝的页面闸门 */
        internal fun isPaymentResultPage(texts: List<String>): Boolean =
            ScreenPageParser.isResultPage("com.tencent.mm", texts)

        /** 无障碍是否已授权 */
        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, PaymentAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
