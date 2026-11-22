package com.qinkun.accountbook.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.qinkun.accountbook.data.CaptureLog
import com.qinkun.accountbook.data.Repo
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.util.PiiMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotifyListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: Repo

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val appName = TransactionParser.APP_NAMES[pkg] ?: return

        val n: Notification = sbn.notification ?: return
        // v4.0：分组摘要通知（「微信 · 3 条新消息」那一条）只是子通知的复述，
        // 内容和子通知重复，又经常把几笔交易拼在一起，金额会取错 —— 只认子通知。
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        // 常驻/进度类通知（外卖骑手进度、下载进度、音乐播放）会每隔几秒刷新一次，
        // 文案里带「实付 ¥35」也不代表又付了一次钱
        if (sbn.isOngoing) return
        val e = n.extras ?: return

        fun str(key: String): String = e.getCharSequence(key)?.toString()?.trim() ?: ""
        val title = str(Notification.EXTRA_TITLE)

        // 微信多条消息被折叠时，真正的交易文字在 EXTRA_TEXT_LINES 里，
        // 只读 text/bigText 会漏掉 —— 例如「[2条] 微信支付: ¥18.00」
        val textLines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            .orEmpty()

        val texts = (listOf(
            str(Notification.EXTRA_TEXT),
            str(Notification.EXTRA_BIG_TEXT),
            str(Notification.EXTRA_SUB_TEXT),
            str(Notification.EXTRA_INFO_TEXT),
        ) + textLines).filter { it.isNotBlank() }.distinct()

        if (texts.isEmpty() && title.isBlank()) return

        var failReason: String? = null
        val parsed = TransactionParser.parse(pkg, title, texts) { failReason = it }
        val at = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()

        // 日志用的原文（脱敏后），失败时也要留下，方便事后排查
        val logTitle = PiiMasker.mask(title)
        val logText = PiiMasker.mask(texts.joinToString(" / "))

        scope.launch {
            try {
                if (parsed == null) {
                    Log.d(TAG, "未记账（${failReason ?: "未知"}）: ${title.take(40)}")
                    repo.addCaptureLog(
                        CaptureLog(
                            at = at, sourceApp = appName,
                            title = logTitle, text = logText,
                            success = false, reason = failReason ?: "未识别",
                        )
                    )
                    return@launch
                }

                // 先看这个商户以前被教过什么分类，没教过才走规则猜。
                // 「天外山」这种供应商名字，词库永远猜不对，靠主人改一次记住。
                // 入库存的是脱敏后的商户名，查「记忆」也必须用脱敏后的，否则永远对不上
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
                        note = "",
                        occurredAt = at,
                        rawText = PiiMasker.mask(parsed.rawText),
                    )
                )

                val isDup = id < 0
                val yuan = String.format("%.2f", parsed.amountCents / 100.0)
                val dir = if (parsed.type.name == "EXPENSE") "支出" else "收入"
                repo.addCaptureLog(
                    CaptureLog(
                        at = at, sourceApp = appName,
                        title = logTitle, text = logText,
                        success = !isDup,
                        reason = if (isDup) "重复通知，已跳过（5 分钟内同文本）"
                                 else "$dir ¥$yuan · $category",
                        amountCents = if (isDup) 0 else parsed.amountCents,
                    )
                )

                if (isDup) {
                    Log.d(TAG, "重复通知，跳过: ${parsed.rawText.take(40)}")
                } else {
                    Log.i(TAG, "已记账: ${parsed.sourceApp} $dir $yuan ${parsed.merchant} → $category")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "入库失败", ex)
                runCatching {
                    repo.addCaptureLog(
                        CaptureLog(
                            at = at, sourceApp = appName,
                            title = logTitle, text = logText,
                            success = false, reason = "入库失败：${ex.javaClass.simpleName}",
                        )
                    )
                }
            }
            // 清理 7 天前的日志 —— 节流：最多每 6 小时一次。
            // 每条通知都跑 DELETE 会造成写放大和数据库锁竞争。
            val now = System.currentTimeMillis()
            if (now - lastPruneAt > 6L * 60 * 60 * 1000) {
                lastPruneAt = now
                runCatching { repo.pruneCaptureLogs(7) }
            }
        }
    }

    override fun onDestroy() {
        // Service 被系统重建时会 new 一个新实例；旧实例的 scope 不取消就是纯泄漏，
        // 每次重启攒一条，久了进程里躺一堆死协程。
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "通知监听已断开，尝试请求系统重新绑定")
        // v4.0：断开后主动请求重绑。不请求的话，部分 ROM 上要等到下次重启
        // 或用户手动关开一次权限才会恢复 —— 中间这段时间的账全漏。
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(this, NotifyListener::class.java))
        }.onFailure { Log.e(TAG, "请求重绑失败", it) }
    }

    companion object {
        const val TAG = "NotifyListener"
        @Volatile private var lastPruneAt = 0L
    }
}
