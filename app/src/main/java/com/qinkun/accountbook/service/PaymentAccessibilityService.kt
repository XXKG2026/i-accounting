package com.qinkun.accountbook.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
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
 * 扫码支付的自动记账（v2.6 新增，需要用户手动授权无障碍）。
 *
 * 为什么非要它：微信/支付宝的「扫一扫」「付款码」付款，结果直接画在页面上，
 * **一条系统通知都不发**。通知监听和无障碍之外的任何手段都拿不到。
 *
 * 安全边界（这段是承诺，改代码时别破坏）：
 *   1. 配置里只订阅 com.tencent.mm / com.eg.android.AlipayGphone 两个包名
 *   2. 页面必须含「支付成功 / 付款成功 / 已支付 / 交易成功」才算数
 *   3. 命中账单页/聊天页关键词一律跳过（防止翻历史账单时重复记账）
 *   4. 只取文本，不模拟点击、不注入事件、不改别家 App 的任何东西
 *   5. 数据只进本地数据库，不联网
 *
 * 稳定性：
 *   - 60 秒内同一「包名+金额+商户」只记一次（页面重绘会反复回调）
 *   - 入库前走和通知同一条管线（parse → guessCategory → PiiMasker → addIfNew）
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: Repo

    private var lastSignature: String = ""
    private var lastCaptureAt: Long = 0L

    /** 事件节流：朋友圈/聊天刷屏时 contentChanged 一秒能来几十次 */
    private var lastEventAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        repo = Repo(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in PaymentWatcher.PAY_PACKAGES) return

        // 节流：typeWindowContentChanged 在高频刷屏时一秒几十次，
        // 每次都要跨进程遍历节点树 —— 不节流会掉帧甚至 ANR
        val tick = System.currentTimeMillis()
        if (tick - lastEventAt < 250L) return
        lastEventAt = tick

        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(40)
        collectNodeTexts(root, texts)
        if (texts.isEmpty()) return

        if (!isPaymentResultPage(texts)) return

        val appName = TransactionParser.APP_NAMES[pkg] ?: "支付"

        // 先按通知那套解析（金额、方向）
        val parsedBase = TransactionParser.parse(
            pkg = pkg,
            title = "$appName 支付成功",
            texts = texts,
        ) ?: return

        // 扫码结果页是「平铺节点」，没有「在 X 消费」这种介词结构，
        // 所以商户名另走一条：锚点词之后的第一个像名字的文本
        val merchant = TransactionParser.extractMerchantFromScanNodes(texts)
            ?: parsedBase.merchant

        val parsed = parsedBase.copy(merchant = merchant)

        val now = System.currentTimeMillis()
        val sig = "$pkg:${parsed.amountCents}:$merchant"
        // 20 秒：页面重绘的重复回调挡得住，而菜市场连买两摊同样的东西不会被吞
        if (sig == lastSignature && now - lastCaptureAt < 20_000L) return
        lastSignature = sig
        lastCaptureAt = now

        scope.launch {
            runCatching {
                val category = TransactionParser.guessCategory(
                    parsed.sourceApp, parsed.merchant, parsed.rawText, parsed.type
                )
                val id = repo.addIfNew(
                    Transaction(
                        amountCents = parsed.amountCents,
                        type = parsed.type,
                        category = category,
                        merchant = PiiMasker.mask(parsed.merchant),
                        sourceApp = parsed.sourceApp,
                        note = "",
                        occurredAt = now,
                        rawText = PiiMasker.mask(parsed.rawText),
                    )
                )
                val isDup = id < 0
                val yuan = String.format("%.2f", parsed.amountCents / 100.0)

                repo.addCaptureLog(
                    CaptureLog(
                        at = now, sourceApp = parsed.sourceApp,
                        title = "扫码页（无障碍）",
                        text = PiiMasker.mask(texts.joinToString(" / ")),
                        success = !isDup,
                        reason = if (isDup) "重复（20 秒内同金额同商户）" else "支出 ¥$yuan · $category",
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

    /** 递归收文本，限深度和数量，避免大页面把主线程拖住 */
    private fun collectNodeTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int = 0,
    ) {
        if (node == null || depth > 12 || out.size >= 40) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (out.lastOrNull() != it) out.add(it)
        }
        for (i in 0 until node.childCount) {
            runCatching { collectNodeTexts(node.getChild(i), out, depth + 1) }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PayAccessibility"

        /**
         * 这段文本像不像「刚付完款的结果页」。
         *
         * 三道闸缺一不可：
         *   有成功锚点（支付成功/付款成功/已支付/交易成功）
         *   **且** 有结果页特征（完成 / 订单号 / 支付方式…）
         *   **且** 没命中账单、聊天、公众号等黑名单
         *
         * 为啥要第二道：公众号文章、账单流水里也会出现「已支付」「支付成功」，
         * 光看锚点会误记账。
         */
        internal fun isPaymentResultPage(texts: List<String>): Boolean {
            if (texts.isEmpty()) return false
            val joined = texts.joinToString(" ")
            return SUCCESS_ANCHORS.any { joined.contains(it) } &&
                RESULT_PAGE_HINTS.any { joined.contains(it) } &&
                HISTORY_PAGE_MARKERS.none { joined.contains(it) }
        }

        private val SUCCESS_ANCHORS = listOf("支付成功", "付款成功", "已支付", "交易成功")

        /**
         * 命中这些说明在翻历史账单 / 看公众号文章 / 聊天，不是刚付完款。
         *
         * 黑名单只是第二道闸；第一道是下面 [RESULT_PAGE_HINTS] ——
         * 光靠黑名单防不住「公众号文章里恰好写了『支付成功 ¥20』」这种。
         */
        private val HISTORY_PAGE_MARKERS = listOf(
            "账单详情", "月账单", "搜索账单", "查看全部账单", "筛选项", "全部账单",
            "本月支出", "本月收入", "收支明细", "零钱明细", "交易记录", "交易明细",
            "发消息", "按住说话", "转账记录", "公众号", "朋友圈", "看一看", "搜一搜",
        )

        /**
         * 付款结果页几乎一定带这些东西（按钮、订单号、支付时间）。
         * 要求至少命中一个，把「文章/列表里提及金额」这类误判挡在外面。
         */
        private val RESULT_PAGE_HINTS = listOf(
            "完成", "返回商户", "查看详情", "订单号", "交易单号", "支付时间",
            "付款方式", "支付方式",
        )

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
